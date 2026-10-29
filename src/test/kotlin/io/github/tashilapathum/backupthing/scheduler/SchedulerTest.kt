// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scheduler

import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.ScheduleSettings
import io.github.tashilapathum.backupthing.config.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SchedulerTest {
    @Test
    fun `overdue run fires at once and a schedule change re-arms`() =
        runBlocking<Unit> {
            val settings =
                MutableStateFlow(Settings(schedule = ScheduleSettings(mode = ScheduleMode.INTERVAL, intervalHours = 1)))
            var last: Instant? = Instant.now().minus(Duration.ofHours(3))
            val fired = Channel<Unit>(Channel.UNLIMITED)
            val scheduler =
                Scheduler(settings, { last }, {
                    last = Instant.now()
                    fired.send(Unit)
                })
            val scope = CoroutineScope(Dispatchers.Default)
            scheduler.start(scope)
            try {
                withTimeout(5.seconds) { fired.receive() }
                settings.value = Settings(schedule = ScheduleSettings(mode = ScheduleMode.DAILY, dailyTime = "03:00"))
                withTimeout(5.seconds) {
                    while (scheduler.nextRun.value?.let { Duration.between(Instant.now(), it).toMinutes() < 1 } !=
                        false
                    ) {
                        delay(20)
                    }
                }
                val next = assertNotNull(scheduler.nextRun.value)
                assertTrue(Duration.between(Instant.now(), next) <= Duration.ofHours(24))
                settings.value = Settings(schedule = ScheduleSettings(mode = ScheduleMode.MANUAL))
                withTimeout(5.seconds) { while (scheduler.nextRun.value != null) delay(20) }
                assertTrue(fired.tryReceive().isFailure, "no extra runs")
            } finally {
                scope.cancel()
            }
        }
}
