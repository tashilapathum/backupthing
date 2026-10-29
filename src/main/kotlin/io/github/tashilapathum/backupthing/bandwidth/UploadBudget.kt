// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.db.MediaQueries
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Cap(
    val label: String,
) {
    DAILY("daily"),
    MONTHLY("monthly"),
}

/** Bytes uploaded in the current day and month, against the caps (0 = no cap). */
data class Usage(
    val today: Long,
    val thisMonth: Long,
    val dailyCap: Long,
    val monthlyCap: Long,
    val dayResetsAt: ZonedDateTime,
    val monthResetsAt: ZonedDateTime,
) {
    val anyCap: Boolean get() = dailyCap > 0 || monthlyCap > 0
}

/**
 * Hard upload caps. A file may start only if it fits in what is left of every cap, counting the files
 * already uploading; uploaded bytes go into a per-day ledger in the database.
 */
class UploadBudget(
    private val q: MediaQueries,
    settings: BandwidthSettings,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val dailyCap = settings.dailyCapMB * MB
    private val monthlyCap = settings.monthlyCapMB * MB
    private val resetDay = settings.monthResetDay.coerceIn(1, 28)
    private var reserved = 0L

    class Reservation internal constructor(
        val bytes: Long,
    ) {
        internal var open = true
    }

    sealed interface Verdict {
        data class Granted(
            val reservation: Reservation,
        ) : Verdict

        /** Does not fit in what is left; try again after [resumesAt]. */
        data class Wait(
            val cap: Cap,
            val resumesAt: ZonedDateTime,
        ) : Verdict

        /** Bigger than the whole cap, so it can never upload under it. */
        data class TooLarge(
            val cap: Cap,
            val capBytes: Long,
        ) : Verdict
    }

    @Synchronized
    fun tryReserve(bytes: Long): Verdict {
        val u = usage()
        for ((cap, limit, used, resets) in listOf(
            Check(Cap.DAILY, u.dailyCap, u.today, u.dayResetsAt),
            Check(Cap.MONTHLY, u.monthlyCap, u.thisMonth, u.monthResetsAt),
        )) {
            if (limit <= 0) continue
            if (bytes > limit) return Verdict.TooLarge(cap, limit)
            if (used + reserved + bytes > limit) return Verdict.Wait(cap, resets)
        }
        reserved += bytes
        return Verdict.Granted(Reservation(bytes))
    }

    /** Records [actualBytes] as uploaded today and frees the reservation. */
    @Synchronized
    fun commit(
        reservation: Reservation,
        actualBytes: Long,
    ) {
        release(reservation)
        if (actualBytes <= 0) return
        val day = today().toString()
        q.transaction {
            q.insertUsageDay(day)
            q.addUsage(actualBytes, day)
        }
    }

    @Synchronized
    fun release(reservation: Reservation) {
        if (!reservation.open) return
        reservation.open = false
        reserved -= reservation.bytes
    }

    fun usage(): Usage {
        val today = today()
        val monthStart = monthStart(today)
        val nextMonth = monthStart.plusMonths(1)
        return Usage(
            today = between(today, today.plusDays(1)),
            thisMonth = between(monthStart, nextMonth),
            dailyCap = dailyCap,
            monthlyCap = monthlyCap,
            dayResetsAt = today.plusDays(1).atStartOfDay(zone),
            monthResetsAt = nextMonth.atStartOfDay(zone),
        )
    }

    private data class Check(
        val cap: Cap,
        val limit: Long,
        val used: Long,
        val resets: ZonedDateTime,
    )

    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    /** The latest [resetDay] on or before [day]. */
    private fun monthStart(day: LocalDate): LocalDate =
        if (day.dayOfMonth >= resetDay) day.withDayOfMonth(resetDay) else day.minusMonths(1).withDayOfMonth(resetDay)

    private fun between(
        from: LocalDate,
        until: LocalDate,
    ): Long = q.usageBetween(from.toString(), until.toString()).executeAsOne()

    companion object {
        const val MB = 1_000_000L
    }
}
