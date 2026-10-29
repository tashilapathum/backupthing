// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import java.nio.file.Path

enum class FailureKind {
    /** Not logged in / keyring locked. Never counts as an attempt; the run pauses uploads. */
    AUTH_REQUIRED,

    /** Network, server or unknown errors; retried. */
    TRANSIENT,

    /** The service rejected this file; retrying will not help. */
    PERMANENT,
}

sealed interface UploadOutcome {
    data class Uploaded(
        val bytes: Long,
    ) : UploadOutcome

    /** The exact same content is already there. */
    data object AlreadyPresent : UploadOutcome

    data class Failed(
        val kind: FailureKind,
        val message: String,
    ) : UploadOutcome
}

sealed interface AuthStatus {
    data object Ok : AuthStatus

    data class Failed(
        val kind: FailureKind,
        val message: String,
    ) : AuthStatus
}

interface Uploader {
    /**
     * Uploads [local] under its own file name. [relDir] is the source-relative folder (`2026.10.25/Vids`,
     * empty for the root); destinations without folders ignore it.
     */
    suspend fun upload(
        local: Path,
        relDir: String,
    ): UploadOutcome

    /** Where [upload] would put the file, for display and the database. */
    fun remotePath(
        relDir: String,
        name: String,
    ): String

    suspend fun authStatus(): AuthStatus
}
