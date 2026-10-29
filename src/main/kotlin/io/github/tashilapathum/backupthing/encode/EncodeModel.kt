// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.deps.DependencyReport
import java.nio.file.Path

data class Tools(
    val avifenc: Path,
    val ffmpeg: Path,
    val ffprobe: Path,
) {
    companion object {
        fun from(report: DependencyReport): Tools? {
            val avifenc = report.avifenc.path ?: return null
            val ffmpeg = report.ffmpeg.path ?: return null
            val ffprobe = report.ffprobe.path ?: return null
            return Tools(avifenc, ffmpeg, ffprobe)
        }
    }
}

/** Turns a source file into a staged file (or decides to keep the original). */
fun interface Encoder {
    suspend fun encode(
        source: Path,
        relPath: String,
        kind: MediaKind,
        settings: Settings,
        onProgress: (Double) -> Unit,
    ): EncodeResult
}

sealed interface EncodeResult {
    /** A verified compressed file in staging, with the source's timestamp. */
    data class Encoded(
        val output: Path,
        val size: Long,
        val sha256: String,
        val encodeMs: Long,
        val backend: VideoBackend?,
    ) : EncodeResult

    /** Upload the original instead (passthrough type, motion photo as-is, or compression did not help). */
    data class UseOriginal(
        val reason: String,
    ) : EncodeResult
}

class EncodeException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object OutputNaming {
    /**
     * Staged/remote relative path for an encoded file. Only the conventional extension is replaced
     * (`a.jpg` → `a.avif`, `a.mp4` stays), others are kept (`a.png` → `a.png.avif`) so two sources in one
     * folder never map to the same output.
     */
    fun outputRelPath(
        rel: String,
        kind: MediaKind,
    ): String {
        val slash = rel.lastIndexOf('/')
        val dir = rel.substring(0, slash + 1)
        val name = rel.substring(slash + 1)
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.substringBeforeLast('.')
        val out =
            when (kind) {
                MediaKind.PHOTO -> if (ext == "jpg" || ext == "jpeg") "$base.avif" else "$name.avif"
                MediaKind.MOTION_PHOTO -> base.removeSuffix(".MP").removeSuffix(".mp") + ".avif"
                MediaKind.VIDEO -> if (ext == "mp4") name else "$name.mp4"
                MediaKind.PASSTHROUGH -> name
            }
        return dir + out
    }
}
