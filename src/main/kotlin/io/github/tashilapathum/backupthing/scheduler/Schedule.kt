// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scheduler

import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.ScheduleSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Schedule {
    /**
     * When the next scheduled run is due, or null for manual mode. A daily run that was missed (machine
     * off or asleep at the scheduled time) is due immediately; so is an overdue interval run.
     *
     * @param lastRun start time of the most recent run of any kind, null if there never was one.
     */
    fun nextRun(
        schedule: ScheduleSettings,
        lastRun: Instant?,
        now: Instant,
        zone: ZoneId,
    ): Instant? =
        when (schedule.mode) {
            ScheduleMode.MANUAL -> {
                null
            }

            ScheduleMode.DAILY -> {
                val time = LocalTime.parse(schedule.dailyTime)
                val today = ZonedDateTime.of(now.atZone(zone).toLocalDate(), time, zone)
                val mostRecent = if (today.toInstant() <= now) today else today.minusDays(1)
                val upcoming = if (today.toInstant() > now) today else today.plusDays(1)
                if (lastRun != null && lastRun < mostRecent.toInstant()) now else upcoming.toInstant()
            }

            ScheduleMode.INTERVAL -> {
                val period = Duration.ofHours(schedule.intervalHours.toLong())
                val due = (lastRun ?: now).plus(period)
                if (due < now) now else due
            }
        }
}
