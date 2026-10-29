// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.TestFiles
import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.config.ConcurrencySettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.config.StagingLimit
import io.github.tashilapathum.backupthing.db.BackupDatabase
import io.github.tashilapathum.backupthing.db.Database
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.FailureKind
import io.github.tashilapathum.backupthing.upload.UploadOutcome
import io.github.tashilapathum.backupthing.util.FileUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {
    private val base = createTempDirectory("bt-pipe")
    private val source = base.resolve("source")
    private val opened = Database.open(base.resolve("state.db"))
    private val db: BackupDatabase = opened.first
    private val guard = PathGuard(base.resolve("staging"))
    private val encoder = FakeEncoder(guard)
    private val uploader = FakeUploader()
    private val settings =
        Settings(
            sources = SourceSettings(roots = listOf(source.toString()), settleSeconds = 0),
            concurrency = ConcurrencySettings(photos = 2, videos = 1, uploads = 2, maxAttempts = 2),
        )
    private val pipeline = Pipeline(db, encoder, guard, retryBackoff = listOf(kotlin.time.Duration.ZERO))

    init {
        TestFiles.write(source, "2026.10.25/Vids/a.jpg", TestFiles.JPEG + ByteArray(100) { 1 })
        TestFiles.write(source, "2026.10.25/Vids/b.MP.jpg", TestFiles.JPEG + ByteArray(100) { 2 })
        TestFiles.write(source, "2026.10.25/Pics/v.mp4", TestFiles.MP4 + ByteArray(100) { 3 })
        TestFiles.write(source, "raw.dng", ByteArray(50) { 4 })
    }

    @AfterTest
    fun close() = opened.second.close()

    private fun run(
        settings: Settings = this.settings,
        dryRun: Boolean = false,
    ) = runBlocking { pipeline.run(settings, uploader, "test", dryRun) }

    private fun status(rel: String) = db.mediaQueries.selectByPath(source.toString(), rel).executeAsOne()

    private fun stagedFiles() = Files.walk(guard.stagingRoot).use { s -> s.filter { Files.isRegularFile(it) }.count() }

    private fun treeHash(root: Path) =
        Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) }.toList().associate {
                it.toString() to
                    FileUtil.sha256(it)
            }
        }

    @Test
    fun `happy path uploads everything, cleans staging, leaves sources intact, second run is a no-op`() {
        val before = treeHash(source)
        val s = run()
        assertEquals(RunStatus.SUCCESS, s.status)
        assertEquals(3, s.encoded)
        assertEquals(1, s.keptOriginal)
        assertEquals(4, s.uploaded)
        assertEquals(
            setOf("/r/2026.10.25/Vids/a.avif", "/r/2026.10.25/Vids/b.avif", "/r/2026.10.25/Pics/v.mp4", "/r/raw.dng"),
            uploader.remote.keys,
        )
        assertEquals("/r/raw.dng", status("raw.dng").remote_path)
        assertEquals(0, stagedFiles())
        assertEquals(
            listOf(guard.stagingRoot),
            Files.list(guard.stagingRoot.parent).use { it.toList() }.filter {
                it ==
                    guard.stagingRoot
            },
        )
        assertEquals(before, treeHash(source))
        assertEquals(
            4,
            pipeline.state.value.lastSummary
                ?.uploaded,
        )

        val again = run()
        assertEquals(RunStatus.SUCCESS, again.status)
        assertEquals(0, again.encoded + again.uploaded)
        assertEquals(4, encoder.calls.get())
        val runs = db.mediaQueries.recentRuns(5).executeAsList()
        assertEquals(listOf("SUCCESS", "SUCCESS"), runs.map { it.status })
    }

    @Test
    fun `progress counts each file once and reaches the total`() =
        runBlocking<Unit> {
            encoder.failing += "a.jpg"
            uploader.failNext("v.mp4", UploadOutcome.Failed(FailureKind.PERMANENT, "ValidationError: x"))
            val seen = java.util.concurrent.CopyOnWriteArrayList<PipelineState>()
            val watcher = launch(kotlinx.coroutines.Dispatchers.Unconfined) { pipeline.state.collect { seen += it } }
            pipeline.run(settings, uploader, "test")
            watcher.cancel()
            val running = seen.filter { it.running }
            assertEquals(4, running.maxOf { it.total })
            assertEquals(4, running.maxOf { it.settled })
            assertTrue(running.all { it.settled <= it.total })
        }

    @Test
    fun `encode failures retry on the next run and become FAILED at max attempts`() {
        encoder.failing += "a.jpg"
        assertEquals(RunStatus.SUCCESS, run().status, "first failure is not final")
        assertEquals(MediaStatus.NEW, status("2026.10.25/Vids/a.jpg").status)
        assertEquals(1L, status("2026.10.25/Vids/a.jpg").attempts)
        val second = run()
        assertEquals(RunStatus.PARTIAL, second.status)
        assertEquals(1, second.failed)
        val row = status("2026.10.25/Vids/a.jpg")
        assertEquals(MediaStatus.FAILED, row.status)
        assertEquals("boom a.jpg", row.error)

        encoder.failing.clear()
        db.mediaQueries.retryFailed(0)
        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(MediaStatus.DONE, status("2026.10.25/Vids/a.jpg").status)
    }

    @Test
    fun `transient upload errors are retried within the run`() {
        uploader.failNext("a.avif", UploadOutcome.Failed(FailureKind.TRANSIENT, "net"))
        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(MediaStatus.DONE, status("2026.10.25/Vids/a.jpg").status)
        assertEquals(0L, status("2026.10.25/Vids/a.jpg").attempts)
    }

    @Test
    fun `exhausted transient errors keep the encoded file for the next run`() {
        val net = UploadOutcome.Failed(FailureKind.TRANSIENT, "net")
        uploader.failNext("a.avif", net, net)
        run()
        val row = status("2026.10.25/Vids/a.jpg")
        assertEquals(MediaStatus.ENCODED, row.status)
        assertEquals(1L, row.attempts)
        assertEquals(1, stagedFiles())
        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(MediaStatus.DONE, status("2026.10.25/Vids/a.jpg").status)
        assertEquals(4, encoder.calls.get(), "not re-encoded (incl. passthrough)")
    }

    @Test
    fun `repeated network failures pause uploads as OFFLINE`() {
        val net = UploadOutcome.Failed(FailureKind.TRANSIENT, "Unable to connect")
        listOf("a.avif", "b.avif", "v.mp4", "raw.dng").forEach { uploader.failNext(it, net, net) }
        val single = settings.copy(concurrency = settings.concurrency.copy(uploads = 1))
        val s = runBlocking { pipeline.run(single, uploader, "test") }
        assertEquals(RunStatus.OFFLINE, s.status)
        assertEquals(0, s.uploaded)
        val statuses = db.mediaQueries.selectWork(MediaStatus.ENCODED).executeAsList()
        assertEquals(4, statuses.size, "everything stays encoded for later")
        assertTrue(statuses.count { it.attempts == 0L } >= 1, "files after the pause are not charged an attempt")
    }

    @Test
    fun `permanent failure is final and removes the staged file`() {
        uploader.failNext("v.mp4", UploadOutcome.Failed(FailureKind.PERMANENT, "ValidationError: nope"))
        val s = run()
        assertEquals(RunStatus.PARTIAL, s.status)
        assertEquals(MediaStatus.FAILED, status("2026.10.25/Pics/v.mp4").status)
        assertEquals(0, stagedFiles())
    }

    @Test
    fun `login required pauses uploads without charging attempts, next run uploads without re-encoding`() {
        uploader.auth = AuthStatus.Failed(FailureKind.AUTH_REQUIRED, "Failed to load session")
        val s = run()
        assertEquals(RunStatus.AUTH_REQUIRED, s.status)
        assertEquals(0, uploader.calls.get())
        assertEquals(
            4,
            db.mediaQueries
                .selectWork(MediaStatus.ENCODED)
                .executeAsList()
                .count { it.attempts == 0L },
        )

        uploader.auth = AuthStatus.Ok
        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(4, encoder.calls.get())
        assertEquals(4, uploader.remote.size)
    }

    @Test
    fun `login expiring mid-run stops the remaining uploads`() {
        uploader.failNext("a.avif", UploadOutcome.Failed(FailureKind.AUTH_REQUIRED, "session expired"))
        val single = settings.copy(concurrency = settings.concurrency.copy(uploads = 1))
        val s = runBlocking { pipeline.run(single, uploader, "test") }
        assertEquals(RunStatus.AUTH_REQUIRED, s.status)
        assertEquals(MediaStatus.ENCODED, status("2026.10.25/Vids/a.jpg").status)
        assertEquals(0L, status("2026.10.25/Vids/a.jpg").attempts)
    }

    @Test
    fun `dry run encodes but never uploads`() {
        val s = run(dryRun = true)
        assertEquals(RunStatus.SUCCESS, s.status)
        assertEquals(0, uploader.calls.get())
        assertEquals(3, stagedFiles())
        assertEquals(
            4,
            db.mediaQueries
                .selectWork(MediaStatus.ENCODED)
                .executeAsList()
                .size,
        )
    }

    private fun staging(
        limit: StagingLimit,
        files: Int = 10,
        maxMB: Int = 2000,
    ) = settings.copy(
        concurrency = settings.concurrency.copy(stagingLimit = limit, stagingMaxFiles = files, stagingMaxMB = maxMB),
    )

    private fun count(status: MediaStatus) =
        db.mediaQueries
            .selectByStatus(status)
            .executeAsList()
            .size

    @Test
    fun `staging limit keeps compression from running ahead of slow uploads`() {
        uploader.latency = kotlin.time.Duration.parse("150ms")
        val s = run(staging(StagingLimit.FILES, files = 1))
        assertEquals(RunStatus.SUCCESS, s.status)
        assertEquals(4, uploader.remote.size)
        assertEquals(1, encoder.peakStaged.get(), "never more than one compressed file waiting")
        assertEquals(0, stagedFiles())
    }

    @Test
    fun `without a staging limit compression runs ahead`() {
        uploader.latency = kotlin.time.Duration.parse("150ms")
        assertEquals(RunStatus.SUCCESS, run(staging(StagingLimit.NONE)).status)
        assertTrue(encoder.peakStaged.get() > 1, "peak ${encoder.peakStaged.get()}")
    }

    @Test
    fun `paused uploads stop compression at the staging limit, the rest waits for the next run`() {
        uploader.auth = AuthStatus.Failed(FailureKind.AUTH_REQUIRED, "Failed to load session")
        val limited = staging(StagingLimit.FILES, files = 2)
        val s = run(limited)
        assertEquals(RunStatus.AUTH_REQUIRED, s.status)
        assertEquals(2, stagedFiles())
        assertTrue(count(MediaStatus.NEW) >= 1)
        assertTrue(s.message!!.contains(Regex("Staging full: \\d files? waits? for the next run\\.$")), s.message)

        uploader.auth = AuthStatus.Ok
        assertEquals(RunStatus.SUCCESS, run(limited).status)
        assertEquals(4, uploader.remote.size)
        assertEquals(0, stagedFiles())
    }

    @Test
    fun `dry run respects the staging limit`() {
        val s = run(staging(StagingLimit.FILES, files = 2), dryRun = true)
        assertEquals(RunStatus.SUCCESS, s.status)
        assertEquals(0, uploader.calls.get())
        assertEquals(2, stagedFiles())
        assertTrue(count(MediaStatus.NEW) >= 1)
        assertTrue(s.message!!.startsWith("dry run. Staging full:"), s.message)

        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(4, uploader.remote.size)
    }

    @Test
    fun `size limit lets a file bigger than the limit through on its own`() {
        bigFiles() // sources of 2 MB, staged copies just over 1 MB
        uploader.latency = kotlin.time.Duration.parse("50ms")
        val s = run(staging(StagingLimit.SIZE, maxMB = 1))
        assertEquals(RunStatus.SUCCESS, s.status)
        assertEquals(7, uploader.remote.size)
        val peak = encoder.peakStagedBytes.get()
        assertTrue(peak in 1_000_000 until 2_000_000, "one big file staged at a time, peak $peak bytes")
    }

    @Test
    fun `interrupted rows are recovered and missing staged files re-encoded`() {
        run(dryRun = true)
        val q = db.mediaQueries
        q.markStatus(MediaStatus.UPLOADING, 0, status("2026.10.25/Vids/a.jpg").id)
        q.markStatus(MediaStatus.ENCODING, 0, status("2026.10.25/Pics/v.mp4").id)
        Files.delete(guard.stagingRoot.resolve(status("2026.10.25/Vids/b.MP.jpg").out_rel_path!!))

        assertEquals(RunStatus.SUCCESS, run().status)
        assertEquals(4, uploader.remote.size)
        assertEquals(4 + 2, encoder.calls.get(), "v.mp4 (interrupted) and b (staged file lost) encoded again")
    }

    @Test
    fun `cancelling mid-encode reverts rows and records the run as cancelled`() =
        runBlocking {
            encoder.gate = CompletableDeferred()
            val job = async { pipeline.run(settings, uploader, "test") }
            encoder.started.await()
            job.cancelAndJoin()
            assertEquals(
                0,
                db.mediaQueries
                    .selectWork(MediaStatus.ENCODING)
                    .executeAsList()
                    .size,
            )
            assertEquals(
                "CANCELLED",
                db.mediaQueries
                    .recentRuns(1)
                    .executeAsOne()
                    .status,
            )
            encoder.gate = null
            assertEquals(RunStatus.SUCCESS, pipeline.run(settings, uploader, "test").status)
        }

    @Test
    fun `missing source root fails the run without touching rows`() {
        val gone = settings.copy(sources = settings.sources.copy(roots = listOf(base.resolve("nope").toString())))
        val s = runBlocking { pipeline.run(gone, uploader, "test") }
        assertEquals(RunStatus.FAILED, s.status)
        assertEquals(0, encoder.calls.get())
    }

    /** A clock the test can move forward. */
    private class MovableClock(
        var now: java.time.Instant,
    ) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): java.time.Clock = fixed(now, zone)

        override fun instant(): java.time.Instant = now
    }

    private fun bigFiles() =
        (1..3).forEach { TestFiles.write(source, "big/x$it.jpg", TestFiles.JPEG + ByteArray(2_000_000) { 5 }) }

    @Test
    fun `daily cap leaves files that do not fit for the next day`() {
        bigFiles() // each staged copy is just over 1 MB
        val clock = MovableClock(java.time.Instant.parse("2026-10-26T10:00:00Z"))
        val capped =
            Pipeline(db, encoder, guard, clock, listOf(kotlin.time.Duration.ZERO), java.time.ZoneOffset.UTC)
        val withCap = settings.copy(bandwidth = BandwidthSettings(dailyCapMB = 3))

        val first = runBlocking { capped.run(withCap, uploader, "test") }
        assertEquals(RunStatus.CAPPED, first.status)
        assertTrue(first.message!!.startsWith("Daily upload cap reached. 1 file waits until midnight"), first.message)
        val waiting = db.mediaQueries.selectByStatus(MediaStatus.ENCODED).executeAsList()
        assertEquals(1, waiting.size)
        assertEquals(null, waiting.single().error, "waiting for a cap is not an error")
        assertEquals(0L, waiting.single().attempts)
        val today = db.mediaQueries.usageBetween("2026-10-26", "2026-10-27").executeAsOne()
        assertTrue(today in 2_000_000..3_000_000, "recorded $today")

        clock.now = java.time.Instant.parse("2026-10-27T00:05:00Z")
        val next = runBlocking { capped.run(withCap, uploader, "test") }
        assertEquals(RunStatus.SUCCESS, next.status)
        assertEquals(1, next.uploaded)
        assertEquals(7, encoder.calls.get(), "nothing re-encoded")
        assertTrue(
            db.mediaQueries
                .selectByStatus(MediaStatus.ENCODED)
                .executeAsList()
                .isEmpty(),
        )
    }

    @Test
    fun `a file bigger than the cap fails with a clear message`() {
        bigFiles()
        val withCap = settings.copy(bandwidth = BandwidthSettings(dailyCapMB = 1))
        val s = run(withCap)
        assertEquals(RunStatus.PARTIAL, s.status)
        assertEquals(3, s.failed)
        val row = status("big/x1.jpg")
        assertEquals(MediaStatus.FAILED, row.status)
        assertEquals("Larger than the daily upload cap (1 MB). Raise the cap, then retry.", row.error)
        assertEquals(MediaStatus.DONE, status("raw.dng").status, "small files still upload")
        assertEquals(0, stagedFiles())
    }
}
