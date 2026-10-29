// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.MotionPhotoMode
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.probe.FfProbe
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.util.FileUtil
import io.github.tashilapathum.backupthing.verify.Verifier
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns one source file into a verified file in staging, or decides the original should be uploaded.
 * The source is only ever read; all writes go through [guard].
 */
class MediaEncoder(
    tools: Tools,
    private val guard: PathGuard,
    /** Backends that passed the one-frame probe, in preference order. */
    private val workingBackends: List<VideoBackend>,
) : Encoder {
    private val log = LoggerFactory.getLogger(MediaEncoder::class.java)
    private val probe = FfProbe(tools.ffprobe)
    private val images = ImageEncoder(tools.avifenc)
    private val videos = VideoEncoder(tools.ffmpeg)

    fun resolveBackend(preferred: VideoBackend): VideoBackend =
        when {
            preferred == VideoBackend.AUTO -> {
                DependencyCheck.PROBE_ORDER.firstOrNull { it in workingBackends }
                    ?: throw EncodeException("No working AV1 encoder (run `backupthing check`)")
            }

            preferred in workingBackends -> {
                preferred
            }

            else -> {
                throw EncodeException("Video backend $preferred is not available on this machine")
            }
        }

    override suspend fun encode(
        source: Path,
        relPath: String,
        kind: MediaKind,
        settings: Settings,
        onProgress: (Double) -> Unit,
    ): EncodeResult {
        val compression = settings.compression
        if (kind == MediaKind.PASSTHROUGH) return EncodeResult.UseOriginal("passthrough type")
        if (kind == MediaKind.MOTION_PHOTO && compression.motionPhotoMode == MotionPhotoMode.AS_IS) {
            return EncodeResult.UseOriginal("motion photo kept as-is")
        }

        val output = guard.stagedPath(OutputNaming.outputRelPath(relPath, kind))
        val part =
            guard.requireWritable(
                output.resolveSibling(".${output.fileName}.part.${output.fileName.toString().substringAfterLast('.')}"),
            )
        val started = System.nanoTime()
        try {
            val srcInfo = probe.probe(source)
            var backend: VideoBackend? = null
            if (kind == MediaKind.VIDEO) {
                backend = resolveBackend(compression.videoBackend)
                try {
                    videos.encode(source, part, srcInfo, backend, compression, onProgress)
                } catch (e: EncodeException) {
                    if (backend == VideoBackend.SVT_AV1 || VideoBackend.SVT_AV1 !in workingBackends) throw e
                    log.warn("{} failed on {}, retrying with SVT-AV1: {}", relPath, backend, e.message)
                    guard.delete(part)
                    backend = VideoBackend.SVT_AV1
                    videos.encode(source, part, srcInfo, backend, compression, onProgress)
                }
            } else {
                val jobs =
                    (Runtime.getRuntime().availableProcessors() / settings.concurrency.effectivePhotos())
                        .coerceAtLeast(
                            1,
                        )
                images.encode(source, part, compression, dropXmp = kind == MediaKind.MOTION_PHOTO, jobs = jobs)
                onProgress(1.0)
            }

            Verifier.verify(srcInfo, probe.probe(part), part, isVideo = kind == MediaKind.VIDEO)
            if (compression.keepOriginalIfSmaller && Verifier.notSmaller(source, part)) {
                guard.delete(part)
                return EncodeResult.UseOriginal("compressed file was not smaller")
            }

            // Proton Photos uses the file mtime as a video's capture time; Drive shows it as the modification time.
            val timestamp =
                (if (kind == MediaKind.VIDEO) srcInfo.creationTime else null)
                    ?: Files.getLastModifiedTime(source).toInstant()
            FileUtil.setModified(part, timestamp)
            Files.move(part, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val ms = (System.nanoTime() - started) / 1_000_000
            return EncodeResult.Encoded(output, Files.size(output), FileUtil.sha256(output), ms, backend)
        } catch (e: Throwable) {
            runCatching { guard.delete(part) }
            if (e is CancellationException || e is EncodeException) throw e
            throw EncodeException(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
