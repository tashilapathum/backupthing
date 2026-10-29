// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.safety

import io.github.tashilapathum.backupthing.TestFiles
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathGuardTest {
    private val base = createTempDirectory("bt-guard")
    private val source = base.resolve("source")
    private val guard = PathGuard(base.resolve("staging"))

    @Test
    fun `staged paths stay inside staging`() {
        val p = guard.stagedPath("2026/a.avif")
        assertTrue(p.startsWith(guard.stagingRoot))
        assertTrue(Files.isDirectory(p.parent))
    }

    @Test
    fun `traversal and absolute paths are rejected`() {
        assertFailsWith<UnsafePathException> { guard.stagedPath("../source/a.jpg") }
        assertFailsWith<UnsafePathException> { guard.stagedPath("/etc/passwd") }
        assertFailsWith<UnsafePathException> { guard.stagedPath("") }
        assertFailsWith<UnsafePathException> { guard.requireWritable(base.resolve("staging/../source/x")) }
    }

    @Test
    fun `symlink escaping staging is rejected and source survives`() {
        val original = TestFiles.write(source, "keep.jpg", TestFiles.JPEG)
        Files.createSymbolicLink(guard.stagingRoot.resolve("link"), source)
        assertFailsWith<UnsafePathException> { guard.delete(guard.stagingRoot.resolve("link/keep.jpg")) }
        assertFailsWith<UnsafePathException> { guard.stagedPath("link/new.avif") }
        Files.createSymbolicLink(guard.stagingRoot.resolve("file-link"), original)
        assertFailsWith<UnsafePathException> { guard.delete(guard.stagingRoot.resolve("file-link")) }
        assertTrue(Files.exists(original))
    }

    @Test
    fun `delete and prune inside staging`() {
        val p = guard.stagedPath("a/b/c.avif")
        Files.write(p, byteArrayOf(1))
        assertTrue(guard.delete(p))
        guard.pruneEmptyDirs()
        assertFalse(Files.exists(guard.stagingRoot.resolve("a")))
        assertEquals(guard.stagingRoot, guard.stagingRoot.toRealPath())
    }
}
