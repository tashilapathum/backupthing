// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UploadSummary(
    val transferredItems: Int = 0,
    val transferredBytes: Long = 0,
    val skippedItems: Int = 0,
    val failedItems: Int = 0,
    val failures: List<UploadFailure> = emptyList(),
)

@Serializable
data class UploadFailure(
    val name: String = "",
    val error: String = "",
)

/**
 * Parsing and error classification for the `proton-drive` CLI (pre-1.0, so kept in one place).
 * Observed behaviour is documented in `docs/notes.md`; samples are in `src/test/resources/proton-cli/`.
 */
object CliOutput {
    private val json = Json { ignoreUnknownKeys = true }

    /** The JSON summary is the first `{…}` line on stdout; anything after it is human text. */
    fun parseSummary(stdout: String): UploadSummary? =
        stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") }
            ?.let { runCatching { json.decodeFromString(UploadSummary.serializer(), it) }.getOrNull() }

    private val authPatterns =
        listOf(
            "failed to load session",
            "not logged in",
            "please log in",
            "auth login",
            "unauthorized",
            "session expired",
            "invalid refresh token",
        )
    private val transientPatterns =
        listOf(
            "unable to connect",
            "connectionrefused",
            "timed out",
            "timeout",
            "econnreset",
            "network",
            "503",
            "502",
            "429",
        )

    fun classify(
        stderr: String,
        summary: UploadSummary? = null,
    ): FailureKind {
        val text = (stderr + " " + summary?.failures?.joinToString(" ") { it.error }.orEmpty()).lowercase()
        return when {
            authPatterns.any { it in text } -> FailureKind.AUTH_REQUIRED
            transientPatterns.any { it in text } -> FailureKind.TRANSIENT
            summary?.failures?.any { it.error.startsWith("ValidationError") } == true -> FailureKind.PERMANENT
            else -> FailureKind.TRANSIENT
        }
    }

    /** First meaningful line(s) of an error, without Bun stack traces. */
    fun shortError(
        stderr: String,
        summary: UploadSummary? = null,
    ): String {
        summary?.failures?.firstOrNull()?.let { return "${it.name}: ${it.error}".take(500) }
        return stderr
            .lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() && !it.startsWith("at ") && !it.matches(Regex("^\\d+ \\|.*")) &&
                    !it.startsWith("===")
            }.take(3)
            .joinToString(" · ")
            .ifEmpty { "unknown error" }
            .take(500)
    }
}
