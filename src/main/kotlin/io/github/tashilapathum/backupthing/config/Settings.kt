// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val sources: SourceSettings = SourceSettings(),
    val destination: DestinationSettings = DestinationSettings(),
    val compression: CompressionSettings = CompressionSettings(),
    val schedule: ScheduleSettings = ScheduleSettings(),
    val concurrency: ConcurrencySettings = ConcurrencySettings(),
    val bandwidth: BandwidthSettings = BandwidthSettings(),
    val web: WebSettings = WebSettings(),
    val system: SystemSettings = SystemSettings(),
)

@Serializable
data class SourceSettings(
    /** Absolute paths of folders to watch. Empty until configured. */
    val roots: List<String> = emptyList(),
    val photoExtensions: List<String> = listOf("jpg", "jpeg", "png"),
    val videoExtensions: List<String> = listOf("mp4", "mov", "3gp", "mkv", "webm"),
    /** Uploaded unchanged. HEIC is already efficient and avifenc cannot read it. */
    val passthroughExtensions: List<String> = listOf("heic", "heif", "dng", "gif", "avif", "webp"),
    /** A file must be unmodified this long before it is processed (Syncthing may still be writing it). */
    val settleSeconds: Int = 300,
)

@Serializable
data class DestinationSettings(
    val mode: DestinationMode = DestinationMode.DRIVE,
    /** Remote folder in Proton Drive, e.g. `/my-files/Backups/Phone`. Used in [DestinationMode.DRIVE] only. */
    val remoteRoot: String = "/my-files/Backups/Phone",
    val credentialStore: CredentialStore = CredentialStore.KEYCHAIN,
)

@Serializable
enum class DestinationMode {
    /** Folder tree under [DestinationSettings.remoteRoot] mirroring the source folders. */
    @SerialName("drive")
    DRIVE,

    /** The Proton Photos timeline (no folders; capture dates from EXIF / file mtime). */
    @SerialName("photos")
    PHOTOS,
}

@Serializable
enum class CredentialStore {
    @SerialName("keychain")
    KEYCHAIN,

    @SerialName("pass")
    PASS,
    ;

    /** Value for the CLI's `PROTON_DRIVE_CREDENTIALS_STORE`. */
    val cliValue: String get() = name.lowercase()
}

@Serializable
data class CompressionSettings(
    /** avifenc `-q`, 0..100. */
    val photoQuality: Int = 70,
    /** avifenc `-s`, 0 (slowest) .. 10 (fastest). */
    val photoSpeed: Int = 6,
    val motionPhotoMode: MotionPhotoMode = MotionPhotoMode.STILL_ONLY,
    val videoBackend: VideoBackend = VideoBackend.AUTO,
    /** Per-backend quality (lower = better, 0..63); scales differ, defaults calibrated to VMAF ≈ 97. */
    val nvencCq: Int = 36,
    val svtAv1Crf: Int = 40,
    val vaapiQp: Int = 36,
    val qsvQuality: Int = 36,
    /** If the compressed file is not smaller, upload the original instead. */
    val keepOriginalIfSmaller: Boolean = true,
)

@Serializable
enum class MotionPhotoMode {
    @SerialName("still-only")
    STILL_ONLY,

    @SerialName("as-is")
    AS_IS,
}

@Serializable
enum class VideoBackend {
    @SerialName("auto")
    AUTO,

    @SerialName("nvenc")
    NVENC,

    @SerialName("vaapi")
    VAAPI,

    @SerialName("qsv")
    QSV,

    @SerialName("svt-av1")
    SVT_AV1,
}

@Serializable
data class ScheduleSettings(
    val mode: ScheduleMode = ScheduleMode.DAILY,
    /** Local time `HH:mm`, used when [mode] is DAILY. */
    val dailyTime: String = "03:00",
    /** Used when [mode] is INTERVAL. */
    val intervalHours: Int = 6,
)

@Serializable
enum class ScheduleMode {
    @SerialName("daily")
    DAILY,

    @SerialName("interval")
    INTERVAL,

    @SerialName("manual")
    MANUAL,
}

@Serializable
data class ConcurrencySettings(
    /** 0 means half the available cores. */
    val photos: Int = 0,
    val videos: Int = 2,
    val uploads: Int = 1,
    val maxAttempts: Int = 3,
) {
    fun effectivePhotos(): Int =
        if (photos >
            0
        ) {
            photos
        } else {
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        }
}

@Serializable
data class BandwidthSettings(
    /** Upload speed shared by all uploads, in kB/s (1 kB = 1000 B). 0 means no limit. */
    val uploadLimitKBps: Int = 0,
    /** Most data uploaded per local calendar day, in MB. 0 means no cap. */
    val dailyCapMB: Int = 0,
    /** Most data uploaded per month (from [monthResetDay]), in MB. 0 means no cap. */
    val monthlyCapMB: Int = 0,
    /** Day of the month the monthly cap starts over, 1..28. */
    val monthResetDay: Int = 1,
) {
    companion object {
        /**
         * Each parallel upload must get at least this share of the limit. Measured with CLI 0.9.0: 4 MiB blocks
         * finish at 30 kB/s; at 10 kB/s the CLI keeps restarting them and never completes (docs/notes.md).
         */
        const val MIN_KBPS_PER_UPLOAD = 30
    }
}

@Serializable
data class WebSettings(
    val bindAddress: String = "127.0.0.1",
    val port: Int = 8787,
    /** Required when [bindAddress] is not a loopback address. */
    val accessToken: String = "",
    /** Extra host names the dashboard may be reached by (LAN mode), besides localhost and the bind address. */
    val allowedHosts: List<String> = emptyList(),
) {
    val isLoopbackOnly: Boolean
        get() =
            runCatching {
                java.net.InetAddress
                    .getByName(bindAddress)
                    .isLoopbackAddress
            }.getOrDefault(false)

    /** Where this computer reaches the dashboard: the bind address, or localhost when listening on all addresses. */
    val dashboardUrl: String
        get() {
            val host =
                when (bindAddress) {
                    "0.0.0.0", "::", "[::]", "" -> "localhost"
                    else -> if (':' in bindAddress && !bindAddress.startsWith("[")) "[$bindAddress]" else bindAddress
                }
            return "http://$host:$port"
        }
}

@Serializable
data class SystemSettings(
    /** Overrides the XDG cache location when non-empty. */
    val stagingDir: String = "",
    val logLevel: String = "INFO",
    /** Optional absolute paths for external tools; empty means look up on PATH. */
    val avifencPath: String = "",
    val ffmpegPath: String = "",
    val ffprobePath: String = "",
    val protonDrivePath: String = "",
)
