// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.classify.MediaKind

enum class RunStatus {
    SUCCESS,

    /** Some files failed. */
    PARTIAL,

    /** Uploads paused: not logged in or keyring locked. Encoded files wait for the next run. */
    AUTH_REQUIRED,

    /** Uploads paused after repeated network errors. */
    OFFLINE,

    /** Some files were left waiting for an upload cap to reset. */
    CAPPED,

    /** Nothing processed (e.g. source folder missing). */
    FAILED,
    CANCELLED,
}

data class RunSummary(
    val runId: Long,
    val status: RunStatus,
    val encoded: Int = 0,
    val keptOriginal: Int = 0,
    val uploaded: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    /** Source bytes of the files that reached DONE. */
    val bytesIn: Long = 0,
    /** Bytes actually stored remotely for them. */
    val bytesOut: Long = 0,
    val message: String? = null,
)

enum class Stage { ENCODING, UPLOADING }

data class ActiveFile(
    val id: Long,
    val relPath: String,
    val kind: MediaKind,
    val stage: Stage,
    val progress: Double? = null,
)

data class PipelineState(
    val running: Boolean = false,
    val runId: Long? = null,
    val active: Map<Long, ActiveFile> = emptyMap(),
    /** Files this run started with (to encode + already encoded). */
    val total: Int = 0,
    /** Files that have left this run, whatever the outcome. */
    val settled: Int = 0,
    val toEncode: Int = 0,
    val toUpload: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val uploadsPaused: String? = null,
    /** AUTH_REQUIRED or OFFLINE while [uploadsPaused] is set. */
    val pausedBecause: RunStatus? = null,
    /** Files this run left waiting for an upload cap, and why. */
    val capped: Int = 0,
    val capNote: String? = null,
    val lastSummary: RunSummary? = null,
)

sealed interface PipelineEvent {
    data class RunStarted(
        val runId: Long,
    ) : PipelineEvent

    data class FileDone(
        val id: Long,
        val relPath: String,
        val remotePath: String,
        val alreadyPresent: Boolean,
    ) : PipelineEvent

    data class FileFailed(
        val id: Long,
        val relPath: String,
        val message: String,
        val final: Boolean,
    ) : PipelineEvent

    data class UploadsPaused(
        val reason: String,
    ) : PipelineEvent

    data class RunFinished(
        val summary: RunSummary,
    ) : PipelineEvent
}
