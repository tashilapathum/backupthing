// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scheduler

import io.github.tashilapathum.backupthing.config.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Fires [trigger] according to the schedule in [settings], re-arming whenever the schedule changes.
 * [trigger] should suspend until the run finishes (or return at once if one is already running).
 */
class Scheduler(
    private val settings: StateFlow<Settings>,
    private val lastRun: () -> Instant?,
    private val trigger: suspend () -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)
    private val _nextRun = MutableStateFlow<Instant?>(null)
    val nextRun: StateFlow<Instant?> = _nextRun.asStateFlow()

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            settings.map { it.schedule }.distinctUntilChanged().collectLatest { schedule ->
                while (true) {
                    val next = Schedule.nextRun(schedule, lastRun(), clock.instant(), zone)
                    _nextRun.value = next
                    if (next == null) {
                        log.info("Schedule: manual")
                        awaitCancellation()
                    }
                    log.info("Next run at {}", next.atZone(zone))
                    waitUntil(next)
                    trigger()
                    // Never spin if the trigger returned without a run being recorded.
                    delay(5.seconds)
                }
            }
        }

    /** Short steps against the wall clock, so time spent suspended (laptop sleep) is not added to the wait. */
    private suspend fun waitUntil(at: Instant) {
        while (true) {
            val remaining = java.time.Duration.between(clock.instant(), at)
            if (remaining.isNegative || remaining.isZero) return
            delay(minOf(remaining.toKotlinDuration(), 1.minutes))
        }
    }
}
