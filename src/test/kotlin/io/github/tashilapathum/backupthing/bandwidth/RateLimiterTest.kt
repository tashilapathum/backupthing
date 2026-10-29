// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterTest {
    /** Virtual time: sleeping just moves the clock. */
    private class FakeTime {
        var now = 0L
        var slept = 0L
    }

    private fun limiter(
        rate: Long,
        t: FakeTime,
    ) = RateLimiter(rate, { t.now }, { n ->
        t.now += n
        t.slept += n
    })

    @Test
    fun `sending at the limit takes size over rate`() {
        val t = FakeTime()
        val l = limiter(250_000, t)
        repeat(1_000_000 / 10_000) { l.acquire(10_000) }
        val seconds = t.now / 1e9
        assertTrue(seconds in 3.9..4.0, "took $seconds s")
    }

    @Test
    fun `zero means unlimited`() {
        val t = FakeTime()
        val l = limiter(0, t)
        repeat(1000) { l.acquire(1 shl 20) }
        assertEquals(0, t.slept)
    }

    @Test
    fun `idle time builds up at most one burst`() {
        val t = FakeTime()
        val l = limiter(100_000, t)
        t.now += 60_000_000_000 // a minute idle
        l.acquire(25_000) // the burst (rate / 4) is free
        assertEquals(0, t.slept)
        l.acquire(25_000) // the next quarter second is not
        assertEquals(250_000_000, t.slept)
    }

    @Test
    fun `a new rate applies to the next chunk`() {
        val t = FakeTime()
        val l = limiter(100_000, t)
        l.acquire(100_000)
        l.bytesPerSecond = 0
        val before = t.slept
        l.acquire(1_000_000)
        assertEquals(before, t.slept)
    }
}
