// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.config

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeParseException

data class ValidationError(
    val field: String,
    val message: String,
)

object SettingsValidator {
    private val remoteRootPattern = Regex("^/[^\\u0000]*[^/]$")
    private val extensionPattern = Regex("^[a-z0-9]+$")
    private val hostPattern = Regex("^[A-Za-z0-9.-]+$|^\\[[0-9A-Fa-f:]+]$")
    private val logLevels = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")

    fun validate(settings: Settings): List<ValidationError> =
        buildList {
            with(settings.sources) {
                if (roots.isEmpty()) add(ValidationError("sources.roots", "Add at least one folder"))
                roots.forEachIndexed { i, root -> checkReadableDir("sources.roots[$i]", root)?.let(::add) }
                val all = photoExtensions + videoExtensions + passthroughExtensions
                all.filterNot { extensionPattern.matches(it) }.forEach {
                    add(
                        ValidationError(
                            "sources.extensions",
                            "Invalid extension '$it' (lowercase letters/digits, no dot)",
                        ),
                    )
                }
                all.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
                    add(ValidationError("sources.extensions", "Extension '$it' is listed in more than one group"))
                }
                if (settleSeconds !in
                    0..86_400
                ) {
                    add(ValidationError("sources.settleSeconds", "Use a number from 0 to 86400"))
                }
            }
            with(settings.destination) {
                if (mode == DestinationMode.DRIVE && !remoteRootPattern.matches(remoteRoot)) {
                    add(
                        ValidationError(
                            "destination.remoteRoot",
                            "Use an absolute path, like /my-files/Backups/Phone",
                        ),
                    )
                }
            }
            with(settings.compression) {
                if (photoQuality !in
                    0..100
                ) {
                    add(ValidationError("compression.photoQuality", "Use a number from 0 to 100"))
                }
                if (photoSpeed !in 0..10) add(ValidationError("compression.photoSpeed", "Use a number from 0 to 10"))
                listOf("nvencCq" to nvencCq, "svtAv1Crf" to svtAv1Crf, "vaapiQp" to vaapiQp, "qsvQuality" to qsvQuality)
                    .filter { it.second !in 0..63 }
                    .forEach { add(ValidationError("compression.${it.first}", "Use a number from 0 to 63")) }
            }
            with(settings.schedule) {
                if (mode == ScheduleMode.DAILY) {
                    try {
                        LocalTime.parse(dailyTime)
                    } catch (_: DateTimeParseException) {
                        add(ValidationError("schedule.dailyTime", "Use a 24-hour time like 03:00"))
                    }
                }
                if (mode == ScheduleMode.INTERVAL && intervalHours !in 1..168) {
                    add(ValidationError("schedule.intervalHours", "Use a number from 1 to 168"))
                }
            }
            with(settings.concurrency) {
                if (photos !in 0..64) add(ValidationError("concurrency.photos", "Use a number from 0 to 64 (0 = auto)"))
                if (videos !in 1..16) add(ValidationError("concurrency.videos", "Use a number from 1 to 16"))
                if (uploads !in 1..8) add(ValidationError("concurrency.uploads", "Use a number from 1 to 8"))
                if (maxAttempts !in 1..20) add(ValidationError("concurrency.maxAttempts", "Use a number from 1 to 20"))
                if (stagingMaxFiles !in 1..1000) {
                    add(ValidationError("concurrency.stagingMaxFiles", "Use a number from 1 to 1000"))
                }
                if (stagingMaxMB < 0 || (stagingLimit == StagingLimit.SIZE && stagingMaxMB == 0)) {
                    add(ValidationError("concurrency.stagingMaxMB", "Use a size above 0, or choose No limit"))
                }
            }
            with(settings.bandwidth) {
                val uploads = settings.concurrency.uploads.coerceAtLeast(1)
                val min = BandwidthSettings.MIN_KBPS_PER_UPLOAD * uploads
                if (uploadLimitKBps != 0 && uploadLimitKBps !in min..10_000_000) {
                    val why = if (uploads > 1) " for $uploads parallel uploads" else ""
                    add(
                        ValidationError(
                            "bandwidth.uploadLimitKBps",
                            "Use at least $min kB/s$why, or leave empty for no limit",
                        ),
                    )
                }
                if (dailyCapMB < 0) add(ValidationError("bandwidth.dailyCapMB", "Use a positive number"))
                if (monthlyCapMB < 0) add(ValidationError("bandwidth.monthlyCapMB", "Use a positive number"))
                if (dailyCapMB > 0 && monthlyCapMB > 0 && dailyCapMB > monthlyCapMB) {
                    add(ValidationError("bandwidth.dailyCapMB", "The daily cap is larger than the monthly cap"))
                }
                if (monthResetDay !in 1..28) add(ValidationError("bandwidth.monthResetDay", "Use a day from 1 to 28"))
            }
            with(settings.web) {
                if (port !in 1024..65535) add(ValidationError("web.port", "Use a number from 1024 to 65535"))
                val loopback =
                    runCatching { InetAddress.getByName(bindAddress).isLoopbackAddress }.getOrElse {
                        add(ValidationError("web.bindAddress", "Not a valid IP address or host name"))
                        true
                    }
                allowedHosts.filterNot { hostPattern.matches(it) }.forEach {
                    add(ValidationError("web.allowedHosts", "Invalid host name '$it'"))
                }
                if (!loopback && accessToken.length < 16) {
                    add(
                        ValidationError(
                            "web.accessToken",
                            "Listening beyond this computer needs an access token of at least 16 characters",
                        ),
                    )
                }
            }
            with(settings.system) {
                if (logLevel.uppercase() !in
                    logLevels
                ) {
                    add(ValidationError("system.logLevel", "One of ${logLevels.joinToString()}"))
                }
                if (stagingDir.isNotEmpty() && !Path.of(stagingDir).isAbsolute) {
                    add(ValidationError("system.stagingDir", "Use an absolute path"))
                }
                listOf(
                    "system.avifencPath" to avifencPath,
                    "system.ffmpegPath" to ffmpegPath,
                    "system.ffprobePath" to ffprobePath,
                    "system.protonDrivePath" to protonDrivePath,
                ).filter { it.second.isNotEmpty() }.forEach { (field, p) ->
                    if (!Files.isExecutable(Path.of(p))) add(ValidationError(field, "Not an executable file"))
                }
            }
        }

    private fun checkReadableDir(
        field: String,
        raw: String,
    ): ValidationError? {
        val path = Path.of(raw)
        return when {
            !path.isAbsolute -> ValidationError(field, "Use an absolute path")
            !Files.isDirectory(path) -> ValidationError(field, "Folder does not exist")
            !Files.isReadable(path) -> ValidationError(field, "Folder is not readable")
            else -> null
        }
    }
}
