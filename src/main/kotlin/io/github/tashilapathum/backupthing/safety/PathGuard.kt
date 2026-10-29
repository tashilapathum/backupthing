// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.safety

import java.nio.file.Files
import java.nio.file.Path

class UnsafePathException(
    message: String,
) : SecurityException(message)

/**
 * The only component allowed to create, write or delete files. Everything must resolve (after
 * following symlinks) to a path strictly inside the staging directory, so source media can never
 * be modified, whatever the settings say.
 */
class PathGuard(
    stagingDir: Path,
) {
    val stagingRoot: Path = Files.createDirectories(stagingDir.toAbsolutePath().normalize()).toRealPath()

    /** Returns a writable path for [relative] inside staging, creating parent folders. */
    fun stagedPath(relative: String): Path {
        val candidate = stagingRoot.resolve(relative).normalize()
        requireInside(candidate)
        Files.createDirectories(candidate.parent)
        requireInside(candidate.parent.toRealPath().resolve(candidate.fileName))
        return candidate
    }

    fun requireWritable(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        requireInside(abs)
        val real = if (Files.exists(abs)) abs.toRealPath() else realParent(abs).resolve(abs.fileName)
        requireInside(real)
        return real
    }

    fun delete(path: Path): Boolean = Files.deleteIfExists(requireWritable(path))

    /** Removes empty directories below staging, bottom-up. */
    fun pruneEmptyDirs() {
        Files.walk(stagingRoot).use { s ->
            s
                .filter { it != stagingRoot && Files.isDirectory(it) }
                .sorted(Comparator.reverseOrder())
                .forEach { dir ->
                    Files.newDirectoryStream(dir).use { if (!it.iterator().hasNext()) Files.delete(dir) }
                }
        }
    }

    private fun realParent(abs: Path): Path {
        var p = abs.parent
        while (p != null && !Files.exists(p)) p = p.parent
        val existing = p?.toRealPath() ?: throw UnsafePathException("No existing ancestor for $abs")
        return existing.resolve(p.relativize(abs.parent))
    }

    private fun requireInside(path: Path) {
        if (path == stagingRoot || !path.startsWith(stagingRoot)) {
            throw UnsafePathException("Refusing to write outside staging: $path")
        }
    }
}
