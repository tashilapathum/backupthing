// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val dateTime = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    fun bytes(n: Long): String {
        if (n < 1000) return "$n B"
        val units = listOf("kB", "MB", "GB", "TB")
        var v = n / 1000.0
        var i = 0
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000
            i++
        }
        return if (v >= 100) "%.0f %s".format(Locale.ROOT, v, units[i]) else "%.1f %s".format(Locale.ROOT, v, units[i])
    }

    fun savedPercent(
        original: Long,
        stored: Long,
    ): Int? = if (original > 0) (100 - stored * 100 / original).toInt() else null

    fun time(
        at: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = dateTime.format(at.atZone(zone))

    fun day(
        at: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = date.format(at.atZone(zone))

    fun duration(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min ${s % 60}s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    /** "in 3 h 5 min" / "2 min ago" / "now". */
    fun relative(
        at: Instant,
        now: Instant = Instant.now(),
    ): String {
        val d = Duration.between(now, at)
        val abs = d.abs()
        if (abs.seconds < 30) return "now"
        val text =
            when {
                abs.toMinutes() < 60 -> "${abs.toMinutes()} min"
                abs.toHours() < 48 -> "${abs.toHours()} h ${abs.toMinutesPart()} min"
                else -> "${abs.toDays()} days"
            }
        return if (d.isNegative) "$text ago" else "in $text"
    }
}
