// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.config.ConcurrencySettings
import io.github.tashilapathum.backupthing.config.StagingLimit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagingGateTest {
    private val mb = 1_000_000L

    private fun gate(
        limit: StagingLimit,
        files: Int = 2,
        maxMB: Int = 10,
        staged: Map<Long, Long> = emptyMap(),
    ) = StagingGate(ConcurrencySettings(stagingLimit = limit, stagingMaxFiles = files, stagingMaxMB = maxMB), staged)

    /** Lets other coroutines run until they block. */
    private suspend fun settleDown() = repeat(20) { yield() }

    @Test
    fun `file limit waits for an upload to free a slot`() =
        runBlocking {
            val g = gate(StagingLimit.FILES, files = 2)
            assertTrue(g.reserve(1, mb))
            assertTrue(g.reserve(2, mb))
            g.encoded(1, mb)
            g.encoded(2, mb)
            val third = async { g.reserve(3, mb) }
            settleDown()
            assertFalse(third.isCompleted, "two files staged, the third waits")

            g.uploadSettled(1, stillStaged = false)
            assertTrue(withTimeout(1000) { third.await() })
        }

    @Test
    fun `size limit counts bytes, but an empty staging takes any file`() =
        runBlocking {
            val g = gate(StagingLimit.SIZE, maxMB = 10)
            assertTrue(g.reserve(1, 50 * mb), "staging is empty, so a file bigger than the limit still goes")
            g.encoded(1, 30 * mb)
            val next = async { g.reserve(2, mb) }
            settleDown()
            assertFalse(next.isCompleted)

            g.uploadSettled(1, stillStaged = false)
            assertTrue(withTimeout(1000) { next.await() })
            assertTrue(g.reserve(3, 9 * mb), "1 MB + 9 MB fits in 10 MB")
        }

    @Test
    fun `gives up when staging is full and nothing pending can free it`() =
        runBlocking {
            val g = gate(StagingLimit.FILES, files = 1)
            assertTrue(g.reserve(1, mb))
            g.encoded(1, mb)
            val next = async { g.reserve(2, mb) }
            settleDown()
            assertFalse(next.isCompleted, "an upload is pending, so wait")

            g.uploadSettled(1, stillStaged = true) // e.g. capped or paused: the staged copy stays
            assertFalse(withTimeout(1000) { next.await() })
            assertFalse(g.reserve(3, mb), "still nothing that could free space")
        }

    @Test
    fun `files staged before the run count first`() =
        runBlocking {
            val g = gate(StagingLimit.FILES, files = 1, staged = mapOf(7L to mb))
            g.queued()
            val next = async { g.reserve(1, mb) }
            settleDown()
            assertFalse(next.isCompleted)
            g.uploadSettled(7, stillStaged = false)
            assertTrue(withTimeout(1000) { next.await() })
        }

    @Test
    fun `originals and failed encodes do not hold staging`() =
        runBlocking {
            val g = gate(StagingLimit.FILES, files = 1)
            assertTrue(g.reserve(1, mb))
            g.encoded(1, null)
            assertTrue(g.reserve(2, mb), "the original is uploaded, nothing of file 1 is staged")
            g.encodeFailed(2)
            assertTrue(g.reserve(3, mb))
        }

    @Test
    fun `no limit never waits`() =
        runBlocking {
            val g = gate(StagingLimit.NONE)
            repeat(100) { assertEquals(true, g.reserve(it.toLong(), 1000 * mb)) }
        }
}
