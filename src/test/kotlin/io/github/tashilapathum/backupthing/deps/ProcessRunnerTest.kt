// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.deps

import io.github.tashilapathum.backupthing.process.ProcessRunner
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class ProcessRunnerTest {
    @Test
    fun `captures output and exit code without a shell`() =
        runBlocking {
            val lines = mutableListOf<String>()
            val r = ProcessRunner.run(listOf("printf", "a\\nb; echo injected\\n"), onStdoutLine = { lines += it })
            assertEquals(0, r.exitCode)
            assertEquals(listOf("a", "b; echo injected"), lines)
        }

    @Test
    fun `timeout kills the process`() {
        assertFailsWith<TimeoutCancellationException> {
            runBlocking { ProcessRunner.run(listOf("sleep", "30"), timeout = 200.milliseconds) }
        }
    }

    @Test
    fun `finds tools on PATH`() {
        assertEquals("sh", DependencyCheck.findOnPath("sh")?.fileName.toString())
        assertEquals(null, DependencyCheck.findOnPath("definitely-not-a-tool-xyz"))
    }
}
