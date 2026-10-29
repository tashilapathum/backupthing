// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scheduler

import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.ScheduleSettings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleTest {
    private val colombo = ZoneId.of("Asia/Colombo")
    private val daily = ScheduleSettings(mode = ScheduleMode.DAILY, dailyTime = "03:00")

    private fun at(
        text: String,
        zone: ZoneId = colombo,
    ): Instant = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant()

    @Test
    fun `daily picks today or tomorrow`() {
        assertEquals(
            at("2026-10-26T03:00"),
            Schedule.nextRun(daily, at("2026-10-25T03:00"), at("2026-10-25T17:00"), colombo),
        )
        assertEquals(
            at("2026-10-26T03:00"),
            Schedule.nextRun(daily, at("2026-10-25T03:00"), at("2026-10-26T01:00"), colombo),
        )
        assertEquals(
            at("2026-10-26T03:00"),
            Schedule.nextRun(daily, null, at("2026-10-25T17:00"), colombo),
            "fresh install waits",
        )
    }

    @Test
    fun `missed daily run is due now`() {
        val now = at("2026-10-26T09:00")
        assertEquals(now, Schedule.nextRun(daily, at("2026-10-25T03:00"), now, colombo), "machine was off at 03:00")
        assertEquals(
            at("2026-10-27T03:00"),
            Schedule.nextRun(daily, at("2026-10-26T08:00"), now, colombo),
            "manual run after 03:00 counts",
        )
    }

    @Test
    fun `daily across a DST change keeps local wall time`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // Clocks go back on 2026-10-25 at 03:00 local.
        val next = Schedule.nextRun(daily, at("2026-10-24T03:00", berlin), at("2026-10-24T12:00", berlin), berlin)
        assertEquals(ZonedDateTime.of(2026, 10, 25, 3, 0, 0, 0, berlin).toInstant(), next)
        // 02:30 does not exist on 2026-03-29 in Berlin; java.time moves it to 03:30.
        val gap = ScheduleSettings(mode = ScheduleMode.DAILY, dailyTime = "02:30")
        val spring = Schedule.nextRun(gap, at("2026-03-28T02:30", berlin), at("2026-03-28T12:00", berlin), berlin)
        assertEquals(at("2026-03-29T03:30", berlin), spring)
    }

    @Test
    fun `interval and manual`() {
        val every6 = ScheduleSettings(mode = ScheduleMode.INTERVAL, intervalHours = 6)
        val now = at("2026-10-25T12:00")
        assertEquals(at("2026-10-25T16:00"), Schedule.nextRun(every6, at("2026-10-25T10:00"), now, colombo))
        assertEquals(now, Schedule.nextRun(every6, at("2026-10-24T10:00"), now, colombo), "overdue")
        assertEquals(at("2026-10-25T18:00"), Schedule.nextRun(every6, null, now, colombo))
        assertNull(Schedule.nextRun(ScheduleSettings(mode = ScheduleMode.MANUAL), null, now, colombo))
    }
}
