// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.classify.Classifier
import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.encode.EncodeResult
import io.github.tashilapathum.backupthing.encode.MediaEncoder
import io.github.tashilapathum.backupthing.encode.Tools
import io.github.tashilapathum.backupthing.process.ProcessRunner
import io.github.tashilapathum.backupthing.safety.PathGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class SampleResult(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val inputBytes: Long,
    val outputBytes: Long,
    val encodeMs: Long,
    val backend: VideoBackend?,
    val hasPreview: Boolean,
)

/**
 * "Test on sample file": encodes one source file with the settings being edited into a private temp
 * folder and renders before/after crops. Only files under the configured source folders are accepted.
 */
class SampleTester(
    private val baseDir: Path,
) {
    private val lock = Mutex()
    private val results = ArrayDeque<String>()

    fun previewFile(
        id: String,
        which: String,
    ): Path? {
        if (!ID.matches(id) || which !in setOf("before", "after")) return null
        return baseDir.resolve(id).resolve("$which.png").takeIf { Files.isRegularFile(it) }
    }

    suspend fun test(
        source: Path,
        settings: Settings,
    ): Result<SampleResult> =
        lock.withLock {
            runCatching {
                val file = source.toAbsolutePath().normalize()
                val roots = settings.sources.roots.map { Path.of(it).toAbsolutePath().normalize() }
                require(roots.any { file.startsWith(it) }) { "Pick a file inside one of the source folders" }
                require(Files.isRegularFile(file)) { "File not found" }
                val kind = Classifier(settings.sources).classify(file) ?: error("Not a configured photo or video type")
                require(kind != MediaKind.PASSTHROUGH) { "This type is uploaded unchanged; nothing to test" }

                val report = DependencyCheck(settings.system).run()
                val tools = Tools.from(report) ?: error("avifenc, ffmpeg or ffprobe not found")
                val id = UUID.randomUUID().toString()
                val dir = Files.createDirectories(baseDir.resolve(id))
                val guard = PathGuard(dir.resolve("out"))
                val noFallback = settings.copy(compression = settings.compression.copy(keepOriginalIfSmaller = false))
                val result =
                    MediaEncoder(
                        tools,
                        guard,
                        report.workingVideoBackends,
                    ).encode(file, file.fileName.toString(), kind, noFallback) {}
                check(result is EncodeResult.Encoded) { (result as EncodeResult.UseOriginal).reason }
                val preview =
                    crop(tools.ffmpeg, file, dir.resolve("before.png"), kind) &&
                        crop(tools.ffmpeg, result.output, dir.resolve("after.png"), kind)
                remember(id)
                SampleResult(
                    id,
                    file.fileName.toString(),
                    kind,
                    Files.size(file),
                    result.size,
                    result.encodeMs,
                    result.backend,
                    preview,
                )
            }
        }

    /** Same 640×640 centre crop of the stored pixels (no rotation) from both files, so they line up. */
    private suspend fun crop(
        ffmpeg: Path,
        input: Path,
        out: Path,
        kind: MediaKind,
    ): Boolean {
        val seek = if (kind == MediaKind.VIDEO) listOf("-ss", "1") else emptyList()
        val cmd =
            listOf(ffmpeg.toString(), "-v", "error", "-y", "-noautorotate") + seek +
                listOf(
                    "-i",
                    input.toString(),
                    "-frames:v",
                    "1",
                    "-vf",
                    "crop='min(640,iw)':'min(640,ih)'",
                    "-update",
                    "1",
                    out.toString(),
                )
        return runCatching { ProcessRunner.run(cmd, timeout = 60.seconds).ok }.getOrDefault(false) && Files.exists(out)
    }

    /** Keeps the last few results; older temp folders are deleted. */
    private fun remember(id: String) {
        results.addLast(id)
        while (results.size > KEEP) {
            val old = baseDir.resolve(results.removeFirst())
            if (old.startsWith(baseDir)) old.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val KEEP = 3
        val ID = Regex("^[0-9a-f-]{36}$")
    }
}
