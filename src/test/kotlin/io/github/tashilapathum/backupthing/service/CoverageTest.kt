// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.db.MediaStatus.DONE
import io.github.tashilapathum.backupthing.db.MediaStatus.ENCODED
import io.github.tashilapathum.backupthing.db.MediaStatus.FAILED
import io.github.tashilapathum.backupthing.db.MediaStatus.NEW
import io.github.tashilapathum.backupthing.db.MediaStatus.UPLOADING
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageTest {
    private val phone = "/home/you/Sync/Phone"

    private fun b(
        folder: String,
        status: MediaStatus,
        bytes: Long,
        files: Long = 1,
        root: String = phone,
    ) = Bucket(root, folder, status, files, bytes)

    @Test
    fun `one source gives a row per top-level folder, root files named after the source`() {
        val c =
            Coverage.from(
                listOf(
                    b("Camera", DONE, 600, files = 6),
                    b("Camera", NEW, 300, files = 3),
                    b("Camera", ENCODED, 50),
                    b("Camera", UPLOADING, 50),
                    b("WhatsApp", FAILED, 100),
                    b("", DONE, 400, files = 2),
                ),
                listOf(phone),
            )
        assertEquals(Part(8, 1000), c.total.done)
        assertEquals(Part(2, 100), c.total.ready)
        assertEquals(Part(3, 300), c.total.todo)
        assertEquals(Part(1, 100), c.total.failed)
        assertEquals(1500, c.total.total.bytes)
        assertEquals(66, c.total.percentDone, "1000 of 1500 bytes, rounded down")
        assertEquals(listOf("Camera", "Phone", "WhatsApp"), c.folders.map { it.label }, "largest first")
        val camera = c.folders.first()
        assertEquals(Part(2, 100), camera.ready, "encoded and uploading both wait for upload")
        assertEquals(60, camera.percentDone)
        assertEquals(Part(1, 100), c.folders.last().failed)
    }

    @Test
    fun `several sources give a row per source, with enough path to tell them apart`() {
        val c =
            Coverage.from(
                listOf(
                    b("Camera", DONE, 100, root = "/a/Phone"),
                    b("", NEW, 300, root = "/b/Phone"),
                    b("x", DONE, 50, root = "/c/Tablet/"),
                ),
                listOf("/a/Phone", "/b/Phone", "/c/Tablet"),
            )
        assertEquals(listOf("b/Phone", "a/Phone", "Tablet"), c.folders.map { it.label })
    }

    @Test
    fun `more than five folders fold the smallest into Other`() {
        val c = Coverage.from((1..8).map { b("F$it", DONE, it * 10L) }, listOf(phone))
        assertEquals(listOf("F8", "F7", "F6", "F5", "Other (4 folders)"), c.folders.map { it.label })
        assertEquals(Part(4, 100), c.folders.last().done)
    }

    @Test
    fun `skipped files and removed sources are left out`() {
        val c =
            Coverage.from(
                listOf(
                    b("Camera", DONE, 100),
                    b("Camera", MediaStatus.SKIPPED, 900),
                    b("Old", NEW, 5000, root = "/removed"),
                ),
                listOf("$phone/"),
            )
        assertEquals(Part(1, 100), c.total.total)
        assertEquals(100, c.total.percentDone)
        assertEquals(emptyList(), c.folders, "a single folder would only repeat the total")
    }

    @Test
    fun `an empty library is zero percent`() {
        val c = Coverage.from(emptyList(), listOf(phone))
        assertEquals(0, c.total.percentDone)
        assertEquals(Part(), c.total.total)
    }
}
