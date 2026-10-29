// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.bandwidth.UploadBudget
import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.db.BackupDatabase
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.db.Media_file
import io.github.tashilapathum.backupthing.encode.EncodeResult
import io.github.tashilapathum.backupthing.encode.Encoder
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.scan.Scanner
import io.github.tashilapathum.backupthing.scan.SourceUnavailableException
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.FailureKind
import io.github.tashilapathum.backupthing.upload.UploadOutcome
import io.github.tashilapathum.backupthing.upload.Uploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One backup run: scan → encode → upload → clean up. Every state change is written to the database
 * first, so a crash at any point is recovered by [run] on the next start without duplicate work.
 */
class Pipeline(
    private val db: BackupDatabase,
    private val encoder: Encoder,
    private val guard: PathGuard,
    private val clock: Clock = Clock.systemUTC(),
    /** Waits between in-run retries of a transient upload failure. */
    private val retryBackoff: List<Duration> = listOf(5.seconds, 30.seconds),
    /** Upload caps count local days and months. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val log = LoggerFactory.getLogger(Pipeline::class.java)
    private val q = db.mediaQueries

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private class Counters {
        val encoded = AtomicInteger()
        val keptOriginal = AtomicInteger()
        val uploaded = AtomicInteger()
        val alreadyPresent = AtomicInteger()
        val failed = AtomicInteger()
        val bytesIn = AtomicLong()
        val bytesOut = AtomicLong()
        val consecutiveTransient = AtomicInteger()

        /** Files left ENCODED because they did not fit in an upload cap. */
        val capped = AtomicInteger()

        /** Which cap and when it resets ("daily" to "midnight"), from the first file that had to wait. */
        val capWait = AtomicReference<Pair<String, String>?>(null)

        /** Non-null once uploads are stopped for this run. */
        val paused = AtomicReference<Pair<RunStatus, String>?>(null)
    }

    /** @param dryRun encode into staging but do not upload (files stay ENCODED). */
    suspend fun run(
        settings: Settings,
        uploader: Uploader,
        trigger: String,
        dryRun: Boolean = false,
    ): RunSummary =
        withContext(Dispatchers.IO) {
            val runId =
                q.transactionWithResult {
                    q.recoverInterrupted(now())
                    q.insertRun(now(), trigger)
                    q.lastInsertId().executeAsOne()
                }
            val c = Counters()
            val budget = UploadBudget(q, settings.bandwidth, clock, zone)
            _state.value = PipelineState(running = true, runId = runId, lastSummary = _state.value.lastSummary)
            _events.tryEmit(PipelineEvent.RunStarted(runId))
            log.info("Run {} started ({}{})", runId, trigger, if (dryRun) ", dry run" else "")
            val summary =
                try {
                    execute(runId, settings, uploader, budget, dryRun, c)
                } catch (e: SourceUnavailableException) {
                    RunSummary(runId, RunStatus.FAILED, message = e.message)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { finish(summarize(runId, c, RunStatus.CANCELLED, "cancelled")) }
                    throw e
                }
            finish(summary)
        }

    private suspend fun execute(
        runId: Long,
        settings: Settings,
        uploader: Uploader,
        budget: UploadBudget,
        dryRun: Boolean,
        c: Counters,
    ): RunSummary =
        coroutineScope {
            Scanner(db, settings.sources, clock).scan()
            requeueMissingStaged()

            if (dryRun) {
                c.paused.set(RunStatus.SUCCESS to "dry run")
            } else {
                val auth = uploader.authStatus()
                if (auth is AuthStatus.Failed) pause(c, auth.kind, auth.message)
            }

            val toEncode = q.selectWork(MediaStatus.NEW).executeAsList()
            val readyToUpload = q.selectWork(MediaStatus.ENCODED).executeAsList().map { it.id }
            _state.update {
                it.copy(
                    total = toEncode.size + readyToUpload.size,
                    toEncode = toEncode.size,
                    toUpload = readyToUpload.size,
                )
            }

            val uploads = Channel<Long>(Channel.UNLIMITED)
            readyToUpload.forEach { uploads.trySend(it) }
            val uploadWorkers =
                launch {
                    repeat(settings.concurrency.uploads) {
                        launch {
                            for (id in uploads) {
                                try {
                                    uploadOne(id, settings, uploader, budget, c)
                                } finally {
                                    settle()
                                }
                            }
                        }
                    }
                }

            val photoSlots = Semaphore(settings.concurrency.effectivePhotos())
            val videoSlots = Semaphore(settings.concurrency.videos)
            coroutineScope {
                for (row in toEncode) {
                    launch {
                        val slots = if (row.kind == MediaKind.VIDEO) videoSlots else photoSlots
                        slots.withPermit {
                            if (encodeOne(row, settings, c)) {
                                _state.update { it.copy(toUpload = it.toUpload + 1) }
                                uploads.send(row.id)
                            } else {
                                settle()
                            }
                        }
                    }
                }
            }
            uploads.close()
            uploadWorkers.join()
            guard.pruneEmptyDirs()

            val paused = c.paused.get()
            val status =
                when {
                    paused != null && paused.first != RunStatus.SUCCESS -> paused.first
                    c.failed.get() > 0 -> RunStatus.PARTIAL
                    c.capped.get() > 0 -> RunStatus.CAPPED
                    else -> RunStatus.SUCCESS
                }
            summarize(runId, c, status, paused?.second ?: capMessage(c))
        }

    /** ENCODED rows whose staged file disappeared (cache cleared, crash) are encoded again. */
    private fun requeueMissingStaged() {
        for (row in q.selectWork(MediaStatus.ENCODED).executeAsList()) {
            if (row.upload_original == 0L && !Files.exists(stagedFile(row))) {
                q.revert(MediaStatus.NEW, "staged file missing", now(), row.id)
            }
        }
    }

    private suspend fun encodeOne(
        row: Media_file,
        settings: Settings,
        c: Counters,
    ): Boolean {
        val source = sourceFile(row)
        if (!Files.exists(source)) {
            q.revert(MediaStatus.SKIPPED, "source file no longer exists", now(), row.id)
            return false
        }
        q.markStatus(MediaStatus.ENCODING, now(), row.id)
        setActive(ActiveFile(row.id, row.rel_path, row.kind, Stage.ENCODING, 0.0))
        try {
            val result =
                encoder.encode(source, row.rel_path, row.kind, settings) { p ->
                    _state.update { s ->
                        s.active[row.id]?.let {
                            s.copy(
                                active =
                                    s.active + (row.id to it.copy(progress = p)),
                            )
                        }
                            ?: s
                    }
                }
            when (result) {
                is EncodeResult.Encoded -> {
                    val rel = guard.stagingRoot.relativize(result.output).toString()
                    q.markEncoded(rel, result.size, result.sha256, result.encodeMs, result.backend?.name, now(), row.id)
                    c.encoded.incrementAndGet()
                    log.info(
                        "Encoded {} → {} ({}%)",
                        row.rel_path,
                        rel,
                        100 * result.size / row.src_size.coerceAtLeast(1),
                    )
                }

                is EncodeResult.UseOriginal -> {
                    q.markUseOriginal(0, result.reason, now(), row.id)
                    c.keptOriginal.incrementAndGet()
                    log.info("Keeping original for {}: {}", row.rel_path, result.reason)
                }
            }
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { q.revert(MediaStatus.NEW, null, now(), row.id) }
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            q.recordFailure(message, now(), settings.concurrency.maxAttempts.toLong(), MediaStatus.NEW.name, row.id)
            reportFailure(row, message, c)
            return false
        } finally {
            clearActive(row.id)
            _state.update { it.copy(toEncode = (it.toEncode - 1).coerceAtLeast(0)) }
        }
    }

    private suspend fun uploadOne(
        id: Long,
        settings: Settings,
        uploader: Uploader,
        budget: UploadBudget,
        c: Counters,
    ) {
        _state.update { it.copy(toUpload = (it.toUpload - 1).coerceAtLeast(0)) }
        if (c.paused.get() != null) return
        val row = q.selectById(id).executeAsOneOrNull() ?: return
        if (row.status != MediaStatus.ENCODED) return
        val original = row.upload_original != 0L
        val local = if (original) sourceFile(row) else stagedFile(row)
        if (!Files.exists(local)) {
            val (status, why) =
                if (original) {
                    MediaStatus.SKIPPED to "source file no longer exists"
                } else {
                    MediaStatus.NEW to
                        "staged file missing"
                }
            q.revert(status, why, now(), id)
            return
        }
        val relDir = row.rel_path.substringBeforeLast('/', "")
        val remote = uploader.remotePath(relDir, local.fileName.toString())
        val storedBytes = if (original) Files.size(local) else row.out_size ?: Files.size(local)
        val reservation =
            when (val verdict = budget.tryReserve(Files.size(local))) {
                is UploadBudget.Verdict.Granted -> {
                    verdict.reservation
                }

                is UploadBudget.Verdict.Wait -> {
                    val n = c.capped.incrementAndGet()
                    if (c.capWait.compareAndSet(null, verdict.cap.label to resetLabel(verdict))) {
                        log.info("{} upload cap reached; remaining uploads wait", verdict.cap.label)
                    }
                    _state.update { it.copy(capped = n, capNote = capMessage(c)) }
                    return
                }

                is UploadBudget.Verdict.TooLarge -> {
                    val message =
                        "Larger than the ${verdict.cap.label} upload cap (${formatBytes(verdict.capBytes)}). " +
                            "Raise the cap, then retry."
                    q.markFailed(message, now(), id)
                    if (!original) guard.delete(local)
                    reportFailure(row, message, c, final = true)
                    return
                }
            }
        q.markStatus(MediaStatus.UPLOADING, now(), id)
        setActive(ActiveFile(id, row.rel_path, row.kind, Stage.UPLOADING))
        try {
            var outcome = uploader.upload(local, relDir)
            for (wait in retryBackoff) {
                if (outcome !is UploadOutcome.Failed || outcome.kind != FailureKind.TRANSIENT ||
                    c.paused.get() != null
                ) {
                    break
                }
                log.warn("Upload of {} failed ({}), retrying in {}", row.rel_path, outcome.message, wait)
                delay(wait)
                outcome = uploader.upload(local, relDir)
            }
            when (outcome) {
                is UploadOutcome.Uploaded, UploadOutcome.AlreadyPresent -> {
                    budget.commit(reservation, (outcome as? UploadOutcome.Uploaded)?.bytes ?: 0)
                    q.markDone(remote, now(), now(), id)
                    if (!original) guard.delete(local)
                    c.consecutiveTransient.set(0)
                    if (outcome is UploadOutcome.Uploaded) {
                        c.uploaded.incrementAndGet()
                    } else {
                        c.alreadyPresent
                            .incrementAndGet()
                    }
                    c.bytesIn.addAndGet(row.src_size)
                    c.bytesOut.addAndGet(storedBytes)
                    _state.update { it.copy(done = it.done + 1) }
                    _events.tryEmit(
                        PipelineEvent.FileDone(
                            id,
                            row.rel_path,
                            remote,
                            outcome == UploadOutcome.AlreadyPresent,
                        ),
                    )
                    log.info(
                        "Uploaded {} → {}{}",
                        row.rel_path,
                        remote,
                        if (outcome ==
                            UploadOutcome.AlreadyPresent
                        ) {
                            " (already there)"
                        } else {
                            ""
                        },
                    )
                }

                is UploadOutcome.Failed -> {
                    handleUploadFailure(row, outcome, original, local, settings, c)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { q.revert(MediaStatus.ENCODED, null, now(), id) }
            throw e
        } finally {
            budget.release(reservation)
            clearActive(id)
        }
    }

    /** "Daily upload cap reached. 12 files wait until midnight." */
    private fun capMessage(c: Counters): String? {
        val (cap, until) = c.capWait.get() ?: return null
        val n = c.capped.get()
        return "${cap.replaceFirstChar { it.uppercase() }} upload cap reached. " +
            "$n ${if (n == 1) "file waits" else "files wait"} until $until."
    }

    private fun resetLabel(wait: UploadBudget.Verdict.Wait): String {
        val at = wait.resumesAt
        return if (at.toLocalDate() == LocalDate.now(clock.withZone(zone)).plusDays(1) &&
            at.toLocalTime() == LocalTime.MIDNIGHT
        ) {
            "midnight"
        } else {
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH).format(at)
        }
    }

    /** Caps are set in whole MB. */
    private fun formatBytes(n: Long): String =
        when {
            n % 1_000_000_000 == 0L -> "${n / 1_000_000_000} GB"
            n >= 1_000_000_000 -> "%.1f GB".format(Locale.ROOT, n / 1e9)
            else -> "${n / 1_000_000} MB"
        }

    private fun handleUploadFailure(
        row: Media_file,
        failure: UploadOutcome.Failed,
        original: Boolean,
        local: Path,
        settings: Settings,
        c: Counters,
    ) {
        when (failure.kind) {
            FailureKind.AUTH_REQUIRED -> {
                q.revert(MediaStatus.ENCODED, "login required: ${failure.message}", now(), row.id)
                pause(c, failure.kind, failure.message)
            }

            FailureKind.TRANSIENT -> {
                q.recordFailure(
                    failure.message,
                    now(),
                    settings.concurrency.maxAttempts.toLong(),
                    MediaStatus.ENCODED.name,
                    row.id,
                )
                val final = q.selectById(row.id).executeAsOne().status == MediaStatus.FAILED
                if (final && !original) guard.delete(local)
                reportFailure(row, failure.message, c, final)
                if (c.consecutiveTransient.incrementAndGet() >= OFFLINE_AFTER) pause(c, failure.kind, failure.message)
            }

            FailureKind.PERMANENT -> {
                q.markFailed(failure.message, now(), row.id)
                if (!original) guard.delete(local)
                reportFailure(row, failure.message, c, final = true)
            }
        }
    }

    private fun pause(
        c: Counters,
        kind: FailureKind,
        message: String,
    ) {
        val (status, reason) =
            if (kind == FailureKind.AUTH_REQUIRED) {
                RunStatus.AUTH_REQUIRED to "Login required. Run `proton-drive auth login` ($message)"
            } else {
                RunStatus.OFFLINE to "Uploads paused after repeated errors: $message"
            }
        if (c.paused.compareAndSet(null, status to reason)) {
            log.warn(reason)
            _state.update { it.copy(uploadsPaused = reason, pausedBecause = status) }
            _events.tryEmit(PipelineEvent.UploadsPaused(reason))
        }
    }

    private fun reportFailure(
        row: Media_file,
        message: String,
        c: Counters,
        final: Boolean = q.selectById(row.id).executeAsOne().status == MediaStatus.FAILED,
    ) {
        log.warn("{} failed{}: {}", row.rel_path, if (final) " permanently" else " (will retry)", message)
        if (final) {
            c.failed.incrementAndGet()
            _state.update { it.copy(failed = it.failed + 1) }
        }
        _events.tryEmit(PipelineEvent.FileFailed(row.id, row.rel_path, message, final))
    }

    private fun summarize(
        runId: Long,
        c: Counters,
        status: RunStatus,
        message: String?,
    ) = RunSummary(
        runId = runId,
        status = status,
        encoded = c.encoded.get(),
        keptOriginal = c.keptOriginal.get(),
        uploaded = c.uploaded.get(),
        alreadyPresent = c.alreadyPresent.get(),
        failed = c.failed.get(),
        bytesIn = c.bytesIn.get(),
        bytesOut = c.bytesOut.get(),
        message = message,
    )

    private fun finish(summary: RunSummary): RunSummary {
        q.finishRun(
            now(),
            summary.status.name,
            (summary.uploaded + summary.alreadyPresent).toLong(),
            summary.failed.toLong(),
            summary.bytesIn,
            summary.bytesOut,
            summary.message,
            summary.runId,
        )
        _state.update { PipelineState(lastSummary = summary) }
        _events.tryEmit(PipelineEvent.RunFinished(summary))
        log.info("Run {} finished: {}", summary.runId, summary)
        return summary
    }

    private fun settle() = _state.update { it.copy(settled = it.settled + 1) }

    private fun setActive(file: ActiveFile) = _state.update { it.copy(active = it.active + (file.id to file)) }

    private fun clearActive(id: Long) = _state.update { it.copy(active = it.active - id) }

    private fun sourceFile(row: Media_file): Path = Path.of(row.source_root).resolve(row.rel_path)

    private fun stagedFile(row: Media_file): Path = guard.stagingRoot.resolve(requireNotNull(row.out_rel_path))

    private fun now() = clock.millis()

    companion object {
        /** Consecutive files that exhausted their transient retries before uploads stop for this run. */
        const val OFFLINE_AFTER = 3
    }
}
