// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.ConcurrencySettings
import io.github.tashilapathum.backupthing.config.CredentialStore
import io.github.tashilapathum.backupthing.config.DestinationMode
import io.github.tashilapathum.backupthing.config.DestinationSettings
import io.github.tashilapathum.backupthing.config.MotionPhotoMode
import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.ScheduleSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SettingsValidator
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.config.SystemSettings
import io.github.tashilapathum.backupthing.config.ValidationError
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.config.WebSettings
import io.ktor.http.Parameters
import java.math.RoundingMode

/** Wire name of an enum value in forms and TOML (`SVT_AV1` → `svt-av1`). */
val Enum<*>.wire: String get() = name.lowercase().replace('_', '-')

/** A unit offered next to a number field; [factor] converts to the stored unit. */
data class QuantityUnit(
    val wire: String,
    val label: String,
    val factor: Int,
)

/** Stored as kB/s. */
val SPEED_UNITS = listOf(QuantityUnit("kb", "kB/s", 1), QuantityUnit("mb", "MB/s", 1000))

/** Stored as MB. */
val CAP_UNITS = listOf(QuantityUnit("mb", "MB", 1), QuantityUnit("gb", "GB", 1000))

/**
 * Form fields ⇄ [Settings]. Field names are `section.property`. A field missing from the submitted form
 * keeps its value from the base settings, so partial forms (the setup wizard) work.
 */
class SettingsForm(
    private val params: Parameters,
    private val base: Settings,
) {
    private val errors = mutableListOf<ValidationError>()

    fun parse(): Pair<Settings, List<ValidationError>> {
        val b = base
        val s =
            Settings(
                sources =
                    SourceSettings(
                        roots = lines("sources.roots") ?: b.sources.roots,
                        photoExtensions = list("sources.photoExtensions") ?: b.sources.photoExtensions,
                        videoExtensions = list("sources.videoExtensions") ?: b.sources.videoExtensions,
                        passthroughExtensions =
                            list(
                                "sources.passthroughExtensions",
                            ) ?: b.sources.passthroughExtensions,
                        settleSeconds = int("sources.settleSeconds") ?: b.sources.settleSeconds,
                    ),
                destination =
                    DestinationSettings(
                        mode = enum<DestinationMode>("destination.mode") ?: b.destination.mode,
                        remoteRoot = text("destination.remoteRoot") ?: b.destination.remoteRoot,
                        credentialStore =
                            enum<CredentialStore>("destination.credentialStore") ?: b.destination.credentialStore,
                    ),
                compression =
                    CompressionSettings(
                        photoQuality = int("compression.photoQuality") ?: b.compression.photoQuality,
                        photoSpeed = int("compression.photoSpeed") ?: b.compression.photoSpeed,
                        motionPhotoMode =
                            enum<MotionPhotoMode>("compression.motionPhotoMode") ?: b.compression.motionPhotoMode,
                        videoBackend = enum<VideoBackend>("compression.videoBackend") ?: b.compression.videoBackend,
                        nvencCq = int("compression.nvencCq") ?: b.compression.nvencCq,
                        svtAv1Crf = int("compression.svtAv1Crf") ?: b.compression.svtAv1Crf,
                        vaapiQp = int("compression.vaapiQp") ?: b.compression.vaapiQp,
                        qsvQuality = int("compression.qsvQuality") ?: b.compression.qsvQuality,
                        keepOriginalIfSmaller =
                            checkbox("compression.keepOriginalIfSmaller") ?: b.compression.keepOriginalIfSmaller,
                    ),
                schedule =
                    ScheduleSettings(
                        mode = enum<ScheduleMode>("schedule.mode") ?: b.schedule.mode,
                        dailyTime = text("schedule.dailyTime") ?: b.schedule.dailyTime,
                        intervalHours = int("schedule.intervalHours") ?: b.schedule.intervalHours,
                    ),
                concurrency =
                    ConcurrencySettings(
                        photos = int("concurrency.photos") ?: b.concurrency.photos,
                        videos = int("concurrency.videos") ?: b.concurrency.videos,
                        uploads = int("concurrency.uploads") ?: b.concurrency.uploads,
                        maxAttempts = int("concurrency.maxAttempts") ?: b.concurrency.maxAttempts,
                    ),
                bandwidth =
                    BandwidthSettings(
                        uploadLimitKBps =
                            quantity("bandwidth.uploadLimitKBps", SPEED_UNITS) ?: b.bandwidth.uploadLimitKBps,
                        dailyCapMB = quantity("bandwidth.dailyCapMB", CAP_UNITS) ?: b.bandwidth.dailyCapMB,
                        monthlyCapMB = quantity("bandwidth.monthlyCapMB", CAP_UNITS) ?: b.bandwidth.monthlyCapMB,
                        monthResetDay = int("bandwidth.monthResetDay") ?: b.bandwidth.monthResetDay,
                    ),
                web =
                    WebSettings(
                        bindAddress = text("web.bindAddress") ?: b.web.bindAddress,
                        port = int("web.port") ?: b.web.port,
                        accessToken = params["web.accessToken"]?.trim() ?: b.web.accessToken,
                        allowedHosts = list("web.allowedHosts", lowercase = true) ?: b.web.allowedHosts,
                    ),
                system =
                    SystemSettings(
                        stagingDir = params["system.stagingDir"]?.trim() ?: b.system.stagingDir,
                        logLevel = text("system.logLevel")?.uppercase() ?: b.system.logLevel,
                        avifencPath = params["system.avifencPath"]?.trim() ?: b.system.avifencPath,
                        ffmpegPath = params["system.ffmpegPath"]?.trim() ?: b.system.ffmpegPath,
                        ffprobePath = params["system.ffprobePath"]?.trim() ?: b.system.ffprobePath,
                        protonDrivePath = params["system.protonDrivePath"]?.trim() ?: b.system.protonDrivePath,
                    ),
            )
        return s to (errors + SettingsValidator.validate(s)).distinct()
    }

    private fun text(name: String): String? = params[name]?.trim()?.takeIf { it.isNotEmpty() }

    private fun lines(name: String): List<String>? =
        params[name]
            ?.lines()
            ?.map { it.trim() }
            ?.map { if (it.length > 1) it.trimEnd('/') else it }
            ?.filter { it.isNotEmpty() }
            ?.distinct()

    private fun list(
        name: String,
        lowercase: Boolean = true,
    ): List<String>? =
        params[name]
            ?.split(',', ' ', '\n')
            ?.map { it.trim().removePrefix(".").let { v -> if (lowercase) v.lowercase() else v } }
            ?.filter { it.isNotEmpty() }
            ?.distinct()

    private fun int(name: String): Int? {
        val raw = params[name]?.trim() ?: return null
        return raw.toIntOrNull() ?: run {
            errors += ValidationError(name, "Use a whole number")
            null
        }
    }

    /** A number in the unit picked in `<name>.unit`, converted to the stored unit. Empty means 0 (no limit). */
    private fun quantity(
        name: String,
        units: List<QuantityUnit>,
    ): Int? {
        val raw = params[name]?.trim() ?: return null
        if (raw.isEmpty()) return 0
        val unit = units.firstOrNull { it.wire == params["$name.unit"] } ?: units.first()
        val value = raw.replace(',', '.').toBigDecimalOrNull()
        if (value == null || value.signum() < 0) {
            errors += ValidationError(name, "Use a number, or leave it empty for no limit")
            return null
        }
        return value
            .multiply(unit.factor.toBigDecimal())
            .setScale(0, RoundingMode.HALF_UP)
            .min(Int.MAX_VALUE.toBigDecimal())
            .toInt()
    }

    /** Checkboxes are absent when unticked, so a hidden `<name>.present` field says the box was on the form. */
    private fun checkbox(name: String): Boolean? = if (params["$name.present"] != null) params[name] != null else null

    private inline fun <reified E : Enum<E>> enum(name: String): E? {
        val raw = params[name]?.trim() ?: return null
        return enumValues<E>().firstOrNull { it.wire == raw } ?: run {
            errors += ValidationError(name, "Unknown value '$raw'")
            null
        }
    }
}
