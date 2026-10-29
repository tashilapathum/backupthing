// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

/**
 * Parses ffmpeg `-progress pipe:1` output (`key=value` lines, blocks ending in `progress=continue|end`)
 * into a 0..1 fraction of [durationSeconds].
 */
class FfmpegProgress(
    private val durationSeconds: Double?,
    private val onProgress: (Double) -> Unit,
) {
    fun onLine(line: String) {
        val key = line.substringBefore('=', "")
        val value = line.substringAfter('=', "")
        when (key) {
            // Despite the name, out_time_ms is also in microseconds.
            "out_time_us", "out_time_ms" -> {
                val us = value.toLongOrNull() ?: return
                val total = durationSeconds?.takeIf { it > 0 } ?: return
                onProgress((us / 1_000_000.0 / total).coerceIn(0.0, 1.0))
            }

            "progress" -> {
                if (value == "end") onProgress(1.0)
            }
        }
    }
}
