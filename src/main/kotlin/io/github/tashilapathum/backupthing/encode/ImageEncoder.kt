// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.process.ProcessRunner
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * JPEG/PNG → AVIF with avifenc. EXIF (date, offset, GPS), ICC and Ultra HDR gain maps are kept; EXIF
 * orientation becomes the AVIF `irot`/`imir` transform.
 */
class ImageEncoder(
    private val avifenc: Path,
) {
    suspend fun encode(
        src: Path,
        out: Path,
        settings: CompressionSettings,
        dropXmp: Boolean,
        jobs: Int,
    ) {
        val cmd = command(avifenc, src, out, settings, dropXmp, jobs)
        val r = ProcessRunner.run(cmd, timeout = 5.minutes)
        if (!r.ok) {
            throw EncodeException(
                "avifenc failed (${r.exitCode}): ${(r.stderr + r.stdout).trim().takeLast(500)}",
            )
        }
    }

    companion object {
        fun command(
            avifenc: Path,
            src: Path,
            out: Path,
            settings: CompressionSettings,
            dropXmp: Boolean,
            jobs: Int,
        ): List<String> =
            buildList {
                add(avifenc.toString())
                addAll(listOf("-q", settings.photoQuality.toString(), "-s", settings.photoSpeed.toString()))
                addAll(listOf("-j", jobs.coerceAtLeast(1).toString()))
                // A motion photo's XMP describes the embedded clip, which the still-only output no longer has.
                if (dropXmp) add("--ignore-xmp")
                add(src.toString())
                add(out.toString())
            }
    }
}
