// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import io.github.tashilapathum.backupthing.config.CredentialStore
import io.github.tashilapathum.backupthing.process.ProcessResult
import io.github.tashilapathum.backupthing.process.ProcessRunner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs the `proton-drive` CLI; replaceable in tests. */
fun interface CliRunner {
    suspend fun run(
        args: List<String>,
        timeout: Duration,
    ): ProcessResult
}

class ProtonCli(
    private val executable: Path,
    credentialStore: CredentialStore,
    /** Local throttling proxy (see ThrottleProxy); null connects directly. */
    proxyUrl: String? = null,
) : CliRunner {
    private val env =
        buildMap {
            put("PROTON_DRIVE_CREDENTIALS_STORE", credentialStore.cliValue)
            proxyUrl?.let { url -> PROXY_VARS.forEach { put(it, url) } }
        }

    override suspend fun run(
        args: List<String>,
        timeout: Duration,
    ): ProcessResult = ProcessRunner.run(listOf(executable.toString()) + args, timeout = timeout, env = env)

    companion object {
        val PROXY_VARS = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

        /**
         * Generous: home upload links can be ~200 kB/s, so only a stall below ~30 kB/s times out. Under a speed
         * limit the floor drops to half of each parallel upload's share of it.
         */
        fun uploadTimeout(
            file: Path,
            limitKBps: Int = 0,
            uploads: Int = 1,
        ): Duration {
            val floor = if (limitKBps > 0) minOf(30_000L, limitKBps * 1000L / uploads.coerceAtLeast(1) / 2) else 30_000L
            return maxOf(10.minutes, (Files.size(file) / floor.coerceAtLeast(1)).seconds)
        }
    }
}

/** Error text from both streams: crashes print the message on stdout and only a separator on stderr. */
private val ProcessResult.errorText: String
    get() = stderr + "\n" + stdout.lineSequence().filterNot { it.trimStart().startsWith("{") }.joinToString("\n")

private fun ProcessResult.failure(summary: UploadSummary? = null) =
    UploadOutcome.Failed(CliOutput.classify(errorText, summary), CliOutput.shortError(errorText, summary))

private suspend fun CliRunner.authCheck(): AuthStatus {
    val r = run(listOf("filesystem", "list", "--json", "/"), 60.seconds)
    return if (r.ok) AuthStatus.Ok else r.failure().let { AuthStatus.Failed(it.kind, it.message) }
}

private fun outcome(r: ProcessResult): UploadOutcome {
    val summary = CliOutput.parseSummary(r.stdout)
    return when {
        r.ok && summary != null && summary.transferredItems > 0 -> UploadOutcome.Uploaded(summary.transferredBytes)
        r.ok && summary != null && summary.skippedItems > 0 -> UploadOutcome.AlreadyPresent
        r.ok -> UploadOutcome.Failed(FailureKind.TRANSIENT, "CLI reported success but nothing was uploaded")
        else -> r.failure(summary)
    }
}

/** "My files" folder tree: `remoteRoot/<relDir>/<name>`, new revisions for changed files. */
class ProtonDriveUploader(
    private val cli: CliRunner,
    remoteRoot: String,
    private val timeout: (Path) -> Duration = { ProtonCli.uploadTimeout(it) },
) : Uploader {
    private val root = remoteRoot.trimEnd('/')
    private val knownFolders = mutableSetOf<String>()
    private val folderLock = Mutex()

    override fun remotePath(
        relDir: String,
        name: String,
    ) = folderOf(relDir) + "/" + name

    private fun folderOf(relDir: String) = if (relDir.isEmpty()) root else "$root/${relDir.trim('/')}"

    override suspend fun authStatus(): AuthStatus = cli.authCheck()

    override suspend fun upload(
        local: Path,
        relDir: String,
    ): UploadOutcome {
        val folder = folderOf(relDir)
        ensureFolder(folder)?.let { return it }
        val r =
            cli.run(
                listOf("filesystem", "upload", "--json", "-f", "create-new-revision", local.toString(), folder),
                timeout(local),
            )
        val result = outcome(r)
        // The folder may have been removed remotely since we cached it.
        if (result is UploadOutcome.Failed && "Node not found" in r.errorText) {
            folderLock.withLock { knownFolders.clear() }
        }
        return result
    }

    /** Creates missing folders one level at a time (the CLI does not create parents). Returns a failure or null. */
    private suspend fun ensureFolder(folder: String): UploadOutcome.Failed? =
        folderLock.withLock {
            if (folder in knownFolders) return null
            val segments = folder.trim('/').split('/')
            // The first segment is a CLI root (/my-files, /devices, ...) that always exists.
            var current = "/" + segments.first()
            for (segment in segments.drop(1)) {
                val parent = current
                current = "$current/$segment"
                if (current in knownFolders) continue
                val info = cli.run(listOf("filesystem", "info", "--json", current), 60.seconds)
                if (!info.ok) {
                    if ("Node not found" !in info.errorText) {
                        return info.failure()
                    }
                    val created = cli.run(listOf("filesystem", "create-folder", "--json", parent, segment), 60.seconds)
                    if (!created.ok && "already exists" !in created.errorText) {
                        return created.failure()
                    }
                }
                knownFolders += current
            }
            null
        }
}

/**
 * The Proton Photos timeline. `skip` dedupes by content, so re-uploads are safe; there are no revisions.
 *
 * The CLI also reports *unsupported* types (e.g. `.dng`) as skipped, with no error. A skip therefore only
 * counts as "already there" for types confirmed to upload; for anything else it is a permanent failure,
 * so a file is never marked backed up without being uploaded.
 */
class ProtonPhotosUploader(
    private val cli: CliRunner,
    private val timeout: (Path) -> Duration = { ProtonCli.uploadTimeout(it) },
) : Uploader {
    override fun remotePath(
        relDir: String,
        name: String,
    ) = "photos:$name"

    override suspend fun authStatus(): AuthStatus = cli.authCheck()

    override suspend fun upload(
        local: Path,
        relDir: String,
    ): UploadOutcome {
        val args = listOf("photo", "upload", "--json", "-c", "skip", local.toString())
        val result = outcome(cli.run(args, timeout(local)))
        val ext =
            local.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        if (result == UploadOutcome.AlreadyPresent && ext !in CONFIRMED_TYPES) {
            return UploadOutcome.Failed(
                FailureKind.PERMANENT,
                "Proton Photos skipped this file (.$ext may not be supported); use the Drive destination for it",
            )
        }
        return result
    }

    companion object {
        /** Types seen uploading successfully with `photo upload` (CLI 0.9.0, 2026-10-25). */
        val CONFIRMED_TYPES = setOf("avif", "mp4", "gif", "webp", "heic")
    }
}
