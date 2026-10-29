// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.process.ProcessRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

sealed interface LoginState {
    data object Idle : LoginState

    data object Starting : LoginState

    /** The user must open [url] (any device) and sign in; the CLI waits. */
    data class Waiting(
        val url: String,
    ) : LoginState

    data object Succeeded : LoginState

    data class Failed(
        val message: String,
    ) : LoginState
}

/**
 * Drives `proton-drive auth login` for the web UI. The CLI prints a sign-in URL and exits 0 once the
 * browser sign-in completes.
 */
class ProtonLogin(
    private val scope: CoroutineScope,
    private val timeout: Duration = 10.minutes,
    private val onSuccess: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(
        command: List<String>,
        env: Map<String, String>,
    ) {
        if (job?.isActive == true) return
        _state.value = LoginState.Starting
        job =
            scope.launch {
                val result =
                    try {
                        ProcessRunner.run(command, timeout = timeout, env = env, onStdoutLine = { line ->
                            URL.find(line)?.let { _state.value = LoginState.Waiting(it.value) }
                        })
                    } catch (_: TimeoutCancellationException) {
                        _state.value =
                            LoginState.Failed("Sign-in was not completed within ${timeout.inWholeMinutes} minutes")
                        return@launch
                    } catch (e: Exception) {
                        _state.value = LoginState.Failed(e.message ?: "Could not start proton-drive")
                        return@launch
                    }
                if (result.ok) {
                    _state.value = LoginState.Succeeded
                    onSuccess()
                } else {
                    val text =
                        (result.stderr + "\n" + result.stdout).lineSequence().map { it.trim() }.filter {
                            it.isNotEmpty() &&
                                !it.startsWith("at ")
                        }
                    _state.value = LoginState.Failed(text.lastOrNull() ?: "proton-drive exited with ${result.exitCode}")
                }
            }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        _state.value = LoginState.Idle
    }

    private companion object {
        val URL = Regex("""https://\S+""")
    }
}
