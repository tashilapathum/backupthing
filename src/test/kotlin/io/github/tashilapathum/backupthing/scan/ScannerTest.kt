// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scan

import io.github.tashilapathum.backupthing.TestFiles
import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.db.Database
import io.github.tashilapathum.backupthing.db.MediaStatus
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScannerTest {
    private val root = createTempDirectory("bt-scan")
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val db = Database.open(null).first
    private val sources = SourceSettings(roots = listOf(root.toString()), settleSeconds = 300)

    private fun scanner() = Scanner(db, sources, clock)

    @Test
    fun `records media, skips syncthing files, hidden dirs and unsettled files`() {
        TestFiles.write(root, "2026.10.25/Pics/v.mp4", TestFiles.MP4)
        TestFiles.write(root, "2026.10.25/Vids/p.jpg", TestFiles.JPEG)
        TestFiles.write(root, "2026.10.25/Vids/m.MP.jpg", TestFiles.JPEG)
        TestFiles.write(root, "2026.10.25/notes.txt", byteArrayOf(1))
        TestFiles.write(root, ".stfolder/x.jpg", TestFiles.JPEG)
        TestFiles.write(root, ".stversions/old.jpg", TestFiles.JPEG)
        TestFiles.write(root, "a/.syncthing.p2.jpg.tmp", TestFiles.JPEG)
        TestFiles.write(root, "a/~syncthing~p3.jpg.tmp", TestFiles.JPEG)
        TestFiles.write(root, "a/fresh.jpg", TestFiles.JPEG, mtime = now.minusSeconds(10))

        val r = scanner().scan()

        assertEquals(ScanResult(added = 3, unsettled = 1, ignored = 1), r)
        val rows =
            db.mediaQueries
                .selectByStatus(MediaStatus.NEW)
                .executeAsList()
                .associateBy { it.rel_path }
        assertEquals(setOf("2026.10.25/Pics/v.mp4", "2026.10.25/Vids/p.jpg", "2026.10.25/Vids/m.MP.jpg"), rows.keys)
        assertEquals(MediaKind.MOTION_PHOTO, rows.getValue("2026.10.25/Vids/m.MP.jpg").kind)
    }

    @Test
    fun `rescan is idempotent and re-queues changed files`() {
        TestFiles.write(root, "p.jpg", TestFiles.JPEG)
        TestFiles.write(root, "q.jpg", TestFiles.JPEG)
        scanner().scan()
        val id =
            db.mediaQueries
                .selectByPath(root.toString(), "p.jpg")
                .executeAsOne()
                .id
        db.mediaQueries.transaction {
            db.mediaQueries.requeueChanged(
                MediaKind.PHOTO,
                6,
                Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                0,
                id,
            )
        }

        assertEquals(ScanResult(unchanged = 2), scanner().scan())

        TestFiles.write(root, "p.jpg", TestFiles.JPEG + byteArrayOf(0, 0))
        assertEquals(ScanResult(changed = 1, unchanged = 1), scanner().scan())
        assertEquals(
            8,
            db.mediaQueries
                .selectById(id)
                .executeAsOne()
                .src_size,
        )
    }

    @Test
    fun `missing or empty root aborts before recording anything`() {
        val other = createTempDirectory("bt-scan-empty")
        TestFiles.write(root, "p.jpg", TestFiles.JPEG)
        Files.createDirectories(other.resolve(".stfolder"))
        val s = Scanner(db, sources.copy(roots = listOf(root.toString(), other.toString())), clock)
        assertFailsWith<SourceUnavailableException> { s.scan() }
        assertEquals(
            0,
            db.mediaQueries
                .selectByStatus(MediaStatus.NEW)
                .executeAsList()
                .size,
        )

        val gone = Scanner(db, sources.copy(roots = listOf("/nonexistent/backupthing")), clock)
        assertFailsWith<SourceUnavailableException> { gone.scan() }
    }
}
