// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.probe.MediaInfo
import io.github.tashilapathum.backupthing.process.ProcessRunner
import java.nio.file.Path
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Any video → AV1 in MP4. Keeps the stored frame + rotation tag (`-noautorotate`), colour range and tags,
 * `creation_time`/`location`, and the first audio track. Pixel's extra data tracks are dropped.
 */
class VideoEncoder(
    private val ffmpeg: Path,
) {
    suspend fun encode(
        src: Path,
        out: Path,
        info: MediaInfo,
        backend: VideoBackend,
        settings: CompressionSettings,
        onProgress: (Double) -> Unit,
    ) {
        val progress = FfmpegProgress(info.durationSeconds, onProgress)
        val cmd = command(ffmpeg, src, out, info, backend, settings)
        val timeout = max(10.minutes.inWholeSeconds, ((info.durationSeconds ?: 0.0) * 20).toLong()).seconds
        val r = ProcessRunner.run(cmd, timeout = timeout, onStdoutLine = progress::onLine)
        if (!r.ok) throw EncodeException("ffmpeg/$backend failed (${r.exitCode}): ${r.stderr.trim().takeLast(800)}")
    }

    companion object {
        /** Audio codecs the MP4 muxer accepts as-is; anything else is re-encoded to AAC. */
        private val MP4_AUDIO = setOf("aac", "mp3", "opus", "alac", "ac3", "eac3", "flac")
        private val UNKNOWN = setOf("unknown", "reserved", "unspecified")

        fun quality(
            backend: VideoBackend,
            s: CompressionSettings,
        ): Int =
            when (backend) {
                VideoBackend.NVENC -> s.nvencCq
                VideoBackend.SVT_AV1 -> s.svtAv1Crf
                VideoBackend.VAAPI -> s.vaapiQp
                VideoBackend.QSV -> s.qsvQuality
                VideoBackend.AUTO -> error("AUTO must be resolved first")
            }

        fun command(
            ffmpeg: Path,
            src: Path,
            out: Path,
            info: MediaInfo,
            backend: VideoBackend,
            settings: CompressionSettings,
        ): List<String> {
            val q = quality(backend, settings).toString()
            val tenBit = info.is10Bit
            return buildList {
                addAll(listOf(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-y"))
                if (backend == VideoBackend.VAAPI) addAll(listOf("-vaapi_device", "/dev/dri/renderD128"))
                addAll(listOf("-noautorotate", "-i", src.toString(), "-map", "0:v:0", "-map", "0:a:0?"))
                when (backend) {
                    VideoBackend.NVENC -> {
                        if (tenBit) addAll(listOf("-pix_fmt", "p010le"))
                        addAll(
                            listOf(
                                "-c:v",
                                "av1_nvenc",
                                "-preset",
                                "p7",
                                "-tune",
                                "hq",
                                "-rc",
                                "vbr",
                                "-cq",
                                q,
                                "-b:v",
                                "0",
                            ),
                        )
                    }

                    VideoBackend.SVT_AV1 -> {
                        if (tenBit) addAll(listOf("-pix_fmt", "yuv420p10le"))
                        addAll(listOf("-c:v", "libsvtav1", "-preset", "6", "-crf", q, "-svtav1-params", "tune=0"))
                    }

                    VideoBackend.VAAPI -> {
                        addAll(listOf("-vf", "format=${if (tenBit) "p010" else "nv12"},hwupload"))
                        addAll(listOf("-c:v", "av1_vaapi", "-rc_mode", "CQP", "-qp", q))
                    }

                    VideoBackend.QSV -> {
                        addAll(listOf("-pix_fmt", if (tenBit) "p010le" else "nv12"))
                        addAll(listOf("-c:v", "av1_qsv", "-global_quality", q))
                    }

                    VideoBackend.AUTO -> {
                        error("AUTO must be resolved first")
                    }
                }
                if (info.colorRange == "pc") addAll(listOf("-color_range", "pc"))
                info.colorPrimaries?.takeIf { it !in UNKNOWN }?.let { addAll(listOf("-color_primaries", it)) }
                info.colorTransfer?.takeIf { it !in UNKNOWN }?.let { addAll(listOf("-color_trc", it)) }
                info.colorSpace?.takeIf { it !in UNKNOWN }?.let { addAll(listOf("-colorspace", it)) }
                when {
                    info.audioCodec == null -> {}

                    info.audioCodec in MP4_AUDIO -> {
                        addAll(listOf("-c:a", "copy"))
                    }

                    else -> {
                        addAll(listOf("-c:a", "aac", "-b:a", "192k"))
                    }
                }
                addAll(listOf("-map_metadata", "0", "-movflags", "+faststart"))
                addAll(listOf("-progress", "pipe:1", "-nostats", "-f", "mp4", out.toString()))
            }
        }
    }
}
