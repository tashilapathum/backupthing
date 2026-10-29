// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.verify

import io.github.tashilapathum.backupthing.probe.MediaInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

class VerifyException(
    message: String,
) : Exception(message)

/** Sanity checks on an encoded file before it may replace the original in the upload. */
object Verifier {
    private const val DURATION_TOLERANCE_S = 0.5

    fun verify(
        source: MediaInfo,
        output: MediaInfo,
        outputFile: Path,
        isVideo: Boolean,
    ) {
        if (!Files.isRegularFile(outputFile) ||
            Files.size(outputFile) == 0L
        ) {
            throw VerifyException("Output missing or empty")
        }
        if (source.width != output.width || source.height != output.height) {
            throw VerifyException("Size changed: ${source.width}x${source.height} → ${output.width}x${output.height}")
        }
        if (isVideo) {
            if (source.rotation !=
                output.rotation
            ) {
                throw VerifyException("Rotation changed: ${source.rotation} → ${output.rotation}")
            }
            val a = source.durationSeconds
            val b = output.durationSeconds
            if (a != null && (b == null || abs(a - b) > DURATION_TOLERANCE_S)) {
                throw VerifyException("Duration changed: $a s → $b s")
            }
            if (source.audioCodec != null && output.audioCodec == null) throw VerifyException("Audio track lost")
        }
    }

    /** True when the compressed file should be discarded in favour of the original. */
    fun notSmaller(
        source: Path,
        output: Path,
    ): Boolean = Files.size(output) >= Files.size(source)
}
