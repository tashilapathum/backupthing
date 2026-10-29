// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

object TestFiles {
    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 0x10)
    val MP4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(8)
    val HEIC = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(8)

    fun write(
        root: Path,
        rel: String,
        bytes: ByteArray,
        mtime: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, bytes)
        Files.setLastModifiedTime(p, FileTime.from(mtime))
        return p
    }
}
