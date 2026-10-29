// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant

object FileUtil {
    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun setModified(
        file: Path,
        time: Instant,
    ) {
        Files.setLastModifiedTime(file, FileTime.from(time))
    }
}
