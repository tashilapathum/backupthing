// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.db.Database
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UploadBudgetTest {
    private val opened = Database.open(null)
    private val q = opened.first.mediaQueries
    private val zone = ZoneId.of("Asia/Colombo")
    private val mb = UploadBudget.MB

    @AfterTest
    fun close() = opened.second.close()

    private fun at(
        date: String,
        time: String = "12:00",
    ): Clock = Clock.fixed(LocalDateTime.parse("${date}T$time").atZone(zone).toInstant(), zone)

    private fun budget(
        clock: Clock,
        daily: Int = 0,
        monthly: Int = 0,
        resetDay: Int = 1,
    ) = UploadBudget(
        q,
        BandwidthSettings(dailyCapMB = daily, monthlyCapMB = monthly, monthResetDay = resetDay),
        clock,
        zone,
    )

    private fun uploaded(
        day: String,
        bytes: Long,
    ) {
        q.insertUsageDay(day)
        q.addUsage(bytes, day)
    }

    @Test
    fun `no caps means everything is granted`() {
        val b = budget(at("2026-10-26"))
        assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(Long.MAX_VALUE / 2))
    }

    @Test
    fun `daily cap counts reservations in flight and commits into today's row`() {
        val b = budget(at("2026-10-26"), daily = 10)
        val first = assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(6 * mb)).reservation
        val wait = assertIs<UploadBudget.Verdict.Wait>(b.tryReserve(5 * mb))
        assertEquals(Cap.DAILY, wait.cap)
        assertEquals(LocalDate.parse("2026-10-27").atStartOfDay(zone), wait.resumesAt)
        assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(4 * mb)).also { b.release(it.reservation) }

        b.commit(first, 7 * mb) // the actual size counts, not the estimate
        assertEquals(7 * mb, b.usage().today)
        assertIs<UploadBudget.Verdict.Wait>(b.tryReserve(4 * mb))
        assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(3 * mb))
    }

    @Test
    fun `failed uploads release their share without counting`() {
        val b = budget(at("2026-10-26"), daily = 10)
        val r = assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(8 * mb)).reservation
        b.release(r)
        b.release(r)
        assertEquals(0, b.usage().today)
        assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(10 * mb))
    }

    @Test
    fun `a file bigger than the whole cap is too large, not waiting`() {
        val b = budget(at("2026-10-26"), daily = 10, monthly = 100)
        val v = assertIs<UploadBudget.Verdict.TooLarge>(b.tryReserve(11 * mb))
        assertEquals(Cap.DAILY, v.cap)
        assertEquals(10 * mb, v.capBytes)
    }

    @Test
    fun `yesterday's usage does not count today`() {
        uploaded("2026-10-25", 10 * mb)
        val b = budget(at("2026-10-26", "00:01"), daily = 10)
        assertEquals(0, b.usage().today)
        assertIs<UploadBudget.Verdict.Granted>(b.tryReserve(10 * mb))
    }

    @Test
    fun `month starts on the reset day`() {
        uploaded("2026-10-14", 50 * mb) // previous cycle
        uploaded("2026-10-15", 30 * mb)
        uploaded("2026-11-14", 20 * mb)
        val b = budget(at("2026-11-14"), monthly = 100, resetDay = 15)
        val u = b.usage()
        assertEquals(50 * mb, u.thisMonth)
        assertEquals(LocalDate.parse("2026-11-15").atStartOfDay(zone), u.monthResetsAt)
        val wait = assertIs<UploadBudget.Verdict.Wait>(b.tryReserve(51 * mb))
        assertEquals(Cap.MONTHLY, wait.cap)

        val next = budget(at("2026-11-15", "00:00"), monthly = 100, resetDay = 15)
        assertEquals(0, next.usage().thisMonth)
    }

    @Test
    fun `reset day 1 uses the calendar month, across the year end`() {
        uploaded("2026-12-31", 5 * mb)
        uploaded("2027-01-01", 7 * mb)
        val u = budget(at("2027-01-20"), monthly = 100).usage()
        assertEquals(7 * mb, u.thisMonth)
        assertEquals(LocalDate.parse("2027-02-01").atStartOfDay(zone), u.monthResetsAt)
    }

    @Test
    fun `concurrent reservations never exceed the cap`() {
        val b = budget(at("2026-10-26"), daily = 100)
        val granted =
            java.util.concurrent.atomic
                .AtomicLong()
        val threads =
            (1..8).map {
                Thread {
                    repeat(50) {
                        val v = b.tryReserve(3 * mb)
                        if (v is UploadBudget.Verdict.Granted) granted.addAndGet(v.reservation.bytes)
                    }
                }.apply { start() }
            }
        threads.forEach { it.join() }
        assertEquals(99 * mb, granted.get())
    }
}
