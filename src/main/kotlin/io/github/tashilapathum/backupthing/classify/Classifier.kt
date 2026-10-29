// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.classify

import io.github.tashilapathum.backupthing.config.SourceSettings
import java.nio.file.Files
import java.nio.file.Path

enum class MediaKind { PHOTO, MOTION_PHOTO, VIDEO, PASSTHROUGH }

/**
 * Decides what to do with a file from its extension, then checks the magic bytes so a mislabelled
 * file (e.g. an `.mp4` saved as `.jpg`) is routed to the right encoder. Folder names are ignored.
 */
class Classifier(
    sources: SourceSettings,
) {
    private val photo = sources.photoExtensions.toSet()
    private val video = sources.videoExtensions.toSet()
    private val passthrough = sources.passthroughExtensions.toSet()

    fun classify(path: Path): MediaKind? {
        val name = path.fileName.toString().lowercase()
        val ext = name.substringAfterLast('.', "")
        val byExtension =
            when (ext) {
                in passthrough -> {
                    return MediaKind.PASSTHROUGH
                }

                in photo -> {
                    if (name.endsWith(".mp.jpg") ||
                        name.endsWith(".mp.jpeg")
                    ) {
                        MediaKind.MOTION_PHOTO
                    } else {
                        MediaKind.PHOTO
                    }
                }

                in video -> {
                    MediaKind.VIDEO
                }

                else -> {
                    return null
                }
            }
        return when (sniff(path)) {
            Sniffed.IMAGE -> if (byExtension == MediaKind.VIDEO) MediaKind.PHOTO else byExtension
            Sniffed.VIDEO -> MediaKind.VIDEO
            Sniffed.UNKNOWN -> byExtension
        }
    }

    private enum class Sniffed { IMAGE, VIDEO, UNKNOWN }

    private fun sniff(path: Path): Sniffed {
        val head = ByteArray(12)
        val n =
            try {
                Files.newInputStream(path).use { it.readNBytes(head, 0, head.size) }
            } catch (_: Exception) {
                return Sniffed.UNKNOWN
            }
        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        ) {
            return Sniffed.IMAGE
        }
        if (n >= 8 && head.copyOfRange(0, 8).contentEquals(PNG)) return Sniffed.IMAGE
        if (n >= 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp") {
            val brand = String(head, 8, 4, Charsets.US_ASCII)
            return if (brand in IMAGE_BRANDS) Sniffed.IMAGE else Sniffed.VIDEO
        }
        if (n >= 4 && head.copyOfRange(0, 4).contentEquals(EBML)) return Sniffed.VIDEO
        return Sniffed.UNKNOWN
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val IMAGE_BRANDS = setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1", "avif", "avis")
    }
}
