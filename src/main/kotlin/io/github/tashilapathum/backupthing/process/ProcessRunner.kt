// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Runs external tools with an argument list (never through a shell). Cancelling the calling
 * coroutine or hitting [timeout] kills the process and its children.
 */
object ProcessRunner {
    private const val MAX_CAPTURE = 1 shl 20

    suspend fun run(
        command: List<String>,
        timeout: Duration = 10.minutes,
        workDir: Path? = null,
        stdin: String? = null,
        env: Map<String, String> = emptyMap(),
        onStdoutLine: ((String) -> Unit)? = null,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(command)
                    .apply {
                        workDir?.let { directory(it.toFile()) }
                        environment().putAll(env)
                    }.start()
            try {
                withTimeout(timeout) {
                    coroutineScope {
                        val out =
                            async(Dispatchers.IO) {
                                val sb = StringBuilder()
                                process.inputStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        onStdoutLine?.invoke(line)
                                        if (sb.length < MAX_CAPTURE) sb.appendLine(line)
                                    }
                                }
                                sb.toString()
                            }
                        val err =
                            async(
                                Dispatchers.IO,
                            ) {
                                process.errorStream
                                    .bufferedReader()
                                    .readText()
                                    .takeLast(MAX_CAPTURE)
                            }
                        process.outputStream.use { os -> stdin?.let { os.write(it.toByteArray()) } }
                        val code = runInterruptible { process.waitFor() }
                        ProcessResult(code, out.await(), err.await())
                    }
                }
            } finally {
                if (process.isAlive) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        }
}
