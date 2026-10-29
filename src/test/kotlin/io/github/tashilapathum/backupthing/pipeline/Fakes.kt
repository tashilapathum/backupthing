// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.encode.EncodeException
import io.github.tashilapathum.backupthing.encode.EncodeResult
import io.github.tashilapathum.backupthing.encode.Encoder
import io.github.tashilapathum.backupthing.encode.OutputNaming
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.UploadOutcome
import io.github.tashilapathum.backupthing.upload.Uploader
import io.github.tashilapathum.backupthing.util.FileUtil
import kotlinx.coroutines.CompletableDeferred
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Writes half of the source bytes into staging; failures and pauses are scriptable per file name. */
class FakeEncoder(
    private val guard: PathGuard,
) : Encoder {
    val calls = AtomicInteger()
    val failing = ConcurrentHashMap.newKeySet<String>()
    val keepOriginal = ConcurrentHashMap.newKeySet<String>()
    var gate: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()

    override suspend fun encode(
        source: Path,
        relPath: String,
        kind: MediaKind,
        settings: Settings,
        onProgress: (Double) -> Unit,
    ): EncodeResult {
        calls.incrementAndGet()
        started.complete(Unit)
        gate?.await()
        val name = source.fileName.toString()
        if (name in failing) throw EncodeException("boom $name")
        if (kind == MediaKind.PASSTHROUGH || name in keepOriginal) return EncodeResult.UseOriginal("test")
        val out = guard.stagedPath(OutputNaming.outputRelPath(relPath, kind))
        val bytes = Files.readAllBytes(source)
        Files.write(out, bytes.copyOf((bytes.size / 2).coerceAtLeast(1)))
        onProgress(1.0)
        return EncodeResult.Encoded(out, Files.size(out), FileUtil.sha256(out), 1, null)
    }
}

/** In-memory remote: name → sha256. [script] outcomes are consumed per file name before normal behaviour. */
class FakeUploader : Uploader {
    val remote = ConcurrentHashMap<String, String>()
    val calls = AtomicInteger()
    val script = ConcurrentHashMap<String, ConcurrentLinkedQueue<UploadOutcome>>()
    var auth: AuthStatus = AuthStatus.Ok

    fun failNext(
        name: String,
        vararg outcomes: UploadOutcome,
    ) {
        script.getOrPut(name) { ConcurrentLinkedQueue() }.addAll(outcomes)
    }

    override fun remotePath(
        relDir: String,
        name: String,
    ) = "/r/" + (if (relDir.isEmpty()) "" else "$relDir/") + name

    override suspend fun authStatus() = auth

    override suspend fun upload(
        local: Path,
        relDir: String,
    ): UploadOutcome {
        calls.incrementAndGet()
        val name = local.fileName.toString()
        script[name]?.poll()?.let { return it }
        val key = remotePath(relDir, name)
        val sha = FileUtil.sha256(local)
        return if (remote.put(key, sha) ==
            sha
        ) {
            UploadOutcome.AlreadyPresent
        } else {
            UploadOutcome.Uploaded(Files.size(local))
        }
    }
}
