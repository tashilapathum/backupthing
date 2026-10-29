// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.pipeline.RunStatus
import io.github.tashilapathum.backupthing.pipeline.Stage
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.FailureKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class Phase(
    val label: String,
) {
    IDLE("Idle"),
    RUNNING("Running"),
    LOGIN("Login required"),
    OFFLINE("Offline"),

    /** The last run left files waiting for an upload cap to reset. Chosen by the user, so not a warning. */
    LIMITED("Upload cap reached"),
    WARNING("Attention"),
}

@Serializable
data class ActiveDto(
    val path: String,
    val stage: String,
    val progress: Double?,
)

@Serializable
data class StatusDto(
    val phase: String,
    val running: Boolean,
    val progressPercent: Int?,
    val active: List<ActiveDto>,
    val queuedEncode: Int,
    val queuedUpload: Int,
    val doneThisRun: Int,
    val failedThisRun: Int,
    val uploadsPaused: String?,
    val nextRun: String?,
    val lastRunStatus: String?,
    val lastRunFinished: String?,
    val totalFiles: Long,
    val originalBytes: Long,
    val storedBytes: Long,
    val pendingFiles: Long,
    val failedFiles: Long,
    val warnings: List<String>,
    val uploadedTodayBytes: Long,
    val uploadedThisMonthBytes: Long,
    /** 0 means no cap / no limit. */
    val dailyCapBytes: Long,
    val monthlyCapBytes: Long,
    val uploadLimitKBps: Int,
)

@Serializable
data class WaybarDto(
    val text: String,
    val alt: String,
    val tooltip: String,
    @SerialName("class") val cssClass: String,
    val percentage: Int,
)

/** One snapshot of everything the dashboard, the JSON API and Waybar show. */
class Snapshot(
    service: BackupService,
) {
    val pipeline = service.pipelineState.value
    val totals = service.totals()

    /** Only the dashboard shows it, so the JSON API and Waybar don't pay for the query. */
    val coverage by lazy { service.coverage() }
    val warnings = service.warnings.value
    val nextRun = service.nextRun.value
    val lastRun = service.recentRuns(1).firstOrNull()
    val usage = service.bandwidthUsage()
    val uploadLimitKBps = service.settings.bandwidth.uploadLimitKBps
    private val auth = service.auth.value

    val runTotal: Int get() = pipeline.total
    val progressPercent: Int? get() = if (pipeline.running && runTotal > 0) pipeline.settled * 100 / runTotal else null

    val phase: Phase =
        when {
            pipeline.running && pipeline.uploadsPaused == null -> Phase.RUNNING
            pipeline.pausedBecause == RunStatus.AUTH_REQUIRED -> Phase.LOGIN
            auth is AuthStatus.Failed && auth.kind == FailureKind.AUTH_REQUIRED -> Phase.LOGIN
            lastRun?.status == "AUTH_REQUIRED" -> Phase.LOGIN
            pipeline.running || auth is AuthStatus.Failed || lastRun?.status == "OFFLINE" -> Phase.OFFLINE
            lastRun?.status == "CAPPED" && totals.pendingFiles > 0 -> Phase.LIMITED
            warnings.isNotEmpty() || totals.failedFiles > 0 -> Phase.WARNING
            else -> Phase.IDLE
        }

    fun toStatus() =
        StatusDto(
            phase = phase.name.lowercase(),
            running = pipeline.running,
            progressPercent = progressPercent,
            active =
                pipeline.active.values
                    .sortedBy {
                        it.id
                    }.map { ActiveDto(it.relPath, it.stage.name.lowercase(), it.progress) },
            queuedEncode = pipeline.toEncode,
            queuedUpload = pipeline.toUpload,
            doneThisRun = pipeline.done,
            failedThisRun = pipeline.failed,
            uploadsPaused = pipeline.uploadsPaused,
            nextRun = nextRun?.toString(),
            lastRunStatus = lastRun?.status,
            lastRunFinished =
                lastRun?.finished_at?.let {
                    java.time.Instant
                        .ofEpochMilli(it)
                        .toString()
                },
            totalFiles = totals.doneFiles,
            originalBytes = totals.doneSourceBytes,
            storedBytes = totals.doneStoredBytes,
            pendingFiles = totals.pendingFiles,
            failedFiles = totals.failedFiles,
            warnings = warnings,
            uploadedTodayBytes = usage.today,
            uploadedThisMonthBytes = usage.thisMonth,
            dailyCapBytes = usage.dailyCap,
            monthlyCapBytes = usage.monthlyCap,
            uploadLimitKBps = uploadLimitKBps,
        )

    fun toWaybar(): WaybarDto {
        val saved = Format.savedPercent(totals.doneSourceBytes, totals.doneStoredBytes)
        val lines =
            buildList {
                add("Backupthing: ${phase.label}")
                if (pipeline.running) {
                    add("Run: ${pipeline.settled}/$runTotal processed, ${pipeline.done} uploaded")
                    pipeline.active.values.forEach {
                        add(
                            "  ${if (it.stage == Stage.ENCODING) "⚙" else "↑"} ${it.relPath}",
                        )
                    }
                }
                pipeline.uploadsPaused?.let { add(it) }
                add(
                    "Backed up: ${totals.doneFiles} files, ${Format.bytes(
                        totals.doneStoredBytes,
                    )}${saved?.let { " ($it% saved)" } ?: ""}",
                )
                if (totals.pendingFiles > 0) add("Pending: ${totals.pendingFiles}")
                if (usage.dailyCap > 0) add("Today: ${Format.bytes(usage.today)} of ${Format.bytes(usage.dailyCap)}")
                if (usage.monthlyCap > 0) {
                    add("This month: ${Format.bytes(usage.thisMonth)} of ${Format.bytes(usage.monthlyCap)}")
                }
                if (totals.failedFiles > 0) add("Failed: ${totals.failedFiles}")
                nextRun?.let { add("Next run: ${Format.time(it)} (${Format.relative(it)})") }
                warnings.forEach { add("⚠ $it") }
            }
        val text =
            when (phase) {
                Phase.RUNNING -> "${pipeline.settled}/$runTotal"
                Phase.LOGIN -> "login"
                Phase.OFFLINE -> "offline"
                Phase.LIMITED -> "limit"
                Phase.WARNING -> if (totals.failedFiles > 0) "${totals.failedFiles} failed" else "!"
                Phase.IDLE -> ""
            }
        return WaybarDto(
            text,
            phase.name.lowercase(),
            lines.joinToString("\n"),
            phase.name.lowercase(),
            progressPercent ?: 0,
        )
    }
}
