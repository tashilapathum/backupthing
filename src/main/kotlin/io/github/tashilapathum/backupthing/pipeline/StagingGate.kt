// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.bandwidth.UploadBudget
import io.github.tashilapathum.backupthing.config.ConcurrencySettings
import io.github.tashilapathum.backupthing.config.StagingLimit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.slf4j.LoggerFactory

/**
 * Keeps staging from growing without bound. Every file being compressed or waiting to upload holds a share of the
 * limit; compression of the next file waits until uploads free enough of it.
 *
 * Space is only freed by uploads, and a file can stay staged for the rest of a run (upload cap, uploads paused,
 * retry on the next run). So [reserve] waits only while an encode or upload is still pending; once nothing can free
 * space it gives up, and the remaining files are left for the next run instead of the run hanging.
 */
class StagingGate(
    private val settings: ConcurrencySettings,
    /** Files already staged when the run starts (row id → bytes); they count first. */
    staged: Map<Long, Long> = emptyMap(),
) {
    private val log = LoggerFactory.getLogger(StagingGate::class.java)
    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    private val held = HashMap(staged)
    private var encoding = 0
    private var pendingUploads = 0
    private var waited = false
    private var gaveUp = false

    /**
     * Reserves room for compressing file [id], whose output is at most [estimate] bytes. Suspends while staging is
     * full. Returns false when it is full and nothing pending can free space: leave the file for the next run.
     */
    suspend fun reserve(
        id: Long,
        estimate: Long,
    ): Boolean {
        while (true) {
            val seen = changes.value
            val granted =
                synchronized(lock) {
                    when {
                        fits(estimate) -> {
                            held[id] = estimate
                            encoding++
                            changed()
                            true
                        }

                        encoding == 0 && pendingUploads == 0 -> {
                            if (!gaveUp) {
                                gaveUp = true
                                log.info(
                                    "Staging is full and nothing can be uploaded now; the rest waits for the next run",
                                )
                            }
                            false
                        }

                        else -> {
                            if (!waited) {
                                waited = true
                                log.info("Staging limit reached ({}); compression waits for uploads", describe())
                            }
                            null
                        }
                    }
                }
            if (granted != null) return granted
            changes.first { it != seen }
        }
    }

    /** Compression finished: [stagedBytes] are now staged (null when the original is uploaded instead) and queued. */
    fun encoded(
        id: Long,
        stagedBytes: Long?,
    ) = synchronized(lock) {
        if (stagedBytes == null) held.remove(id) else held[id] = stagedBytes
        encoding--
        pendingUploads++
        changed()
    }

    /** Compression failed, was skipped or cancelled: nothing of [id] is staged. */
    fun encodeFailed(id: Long) =
        synchronized(lock) {
            held.remove(id)
            encoding--
            changed()
        }

    /** A file staged before this run was queued for upload. */
    fun queued() =
        synchronized(lock) {
            pendingUploads++
            changed()
        }

    /** The upload worker is done with [id]; it keeps its share if its staged copy is still there. */
    fun uploadSettled(
        id: Long,
        stillStaged: Boolean,
    ) = synchronized(lock) {
        if (!stillStaged) held.remove(id)
        pendingUploads--
        changed()
    }

    private fun fits(estimate: Long): Boolean {
        // An empty staging folder always takes the next file, however big, so one large video can't block a run.
        if (held.isEmpty()) return true
        return when (settings.stagingLimit) {
            StagingLimit.FILES -> held.size < settings.stagingMaxFiles
            StagingLimit.SIZE -> held.values.sum() + estimate <= settings.stagingMaxMB * UploadBudget.MB
            StagingLimit.NONE -> true
        }
    }

    private fun describe() =
        when (settings.stagingLimit) {
            StagingLimit.FILES -> {
                val n = settings.stagingMaxFiles
                "$n ${if (n == 1) "file" else "files"}"
            }

            StagingLimit.SIZE -> {
                "${settings.stagingMaxMB} MB"
            }

            StagingLimit.NONE -> {
                "no limit"
            }
        }

    private fun changed() {
        changes.value++
    }
}
