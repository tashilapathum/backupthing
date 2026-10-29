// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import java.util.concurrent.locks.LockSupport

/**
 * Token bucket shared by every connection it throttles. [bytesPerSecond] can change at any time;
 * 0 means unlimited.
 */
class RateLimiter(
    bytesPerSecond: Long = 0,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = LockSupport::parkNanos,
) {
    @Volatile
    var bytesPerSecond: Long = bytesPerSecond
        set(value) {
            field = value.coerceAtLeast(0)
        }

    private var tokens = 0.0
    private var last = nanoTime()

    /**
     * Blocks until [n] bytes may be sent. The bucket can go into debt, so concurrent callers each wait for
     * their own share and the total stays at the rate.
     */
    fun acquire(n: Int) {
        val wait =
            synchronized(this) {
                val rate = bytesPerSecond
                val now = nanoTime()
                val elapsed = now - last
                last = now
                if (rate == 0L) {
                    tokens = 0.0
                    return
                }
                val burst = maxOf(rate / 4, MIN_BURST).toDouble()
                tokens = minOf(burst, tokens + elapsed * rate / 1e9) - n
                if (tokens >= 0) return
                (-tokens * 1e9 / rate).toLong()
            }
        sleepNanos(wait)
    }

    companion object {
        const val MIN_BURST = 16L * 1024
    }
}
