// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.scan

import io.github.tashilapathum.backupthing.classify.Classifier
import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.db.BackupDatabase
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock

class SourceUnavailableException(
    message: String,
) : IOException(message)

data class ScanResult(
    val added: Int = 0,
    val changed: Int = 0,
    val unchanged: Int = 0,
    val unsettled: Int = 0,
    val ignored: Int = 0,
) {
    operator fun plus(o: ScanResult) =
        ScanResult(
            added + o.added,
            changed + o.changed,
            unchanged + o.unchanged,
            unsettled + o.unsettled,
            ignored + o.ignored,
        )
}

/** Walks the source roots read-only and records new or changed media in the database. */
class Scanner(
    private val db: BackupDatabase,
    private val sources: SourceSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(Scanner::class.java)
    private val classifier = Classifier(sources)

    /** Checks every root first, so an unmounted disk aborts the scan before anything is recorded. */
    fun scan(): ScanResult {
        val roots = sources.roots.map { Path.of(it).toAbsolutePath().normalize() }
        roots.forEach(::requireAvailable)
        return roots.fold(ScanResult()) { acc, root -> acc + scanRoot(root) }
    }

    private fun requireAvailable(root: Path) {
        if (!Files.isDirectory(root)) throw SourceUnavailableException("Source folder missing (unmounted?): $root")
        val empty = Files.list(root).use { s -> s.noneMatch { !isIgnoredName(it.fileName.toString()) } }
        if (empty) throw SourceUnavailableException("Source folder is empty (unmounted?): $root")
    }

    private fun scanRoot(root: Path): ScanResult {
        val now = clock.millis()
        val settleBefore = now - sources.settleSeconds * 1000L
        var result = ScanResult()
        val rootKey = root.toString()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir != root &&
                        isIgnoredName(dir.fileName.toString())
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile || isIgnoredName(file.fileName.toString())) return FileVisitResult.CONTINUE
                    val kind = classifier.classify(file)
                    if (kind == null) {
                        result = result.copy(ignored = result.ignored + 1)
                        return FileVisitResult.CONTINUE
                    }
                    val mtime = attrs.lastModifiedTime().toMillis()
                    if (mtime > settleBefore) {
                        result = result.copy(unsettled = result.unsettled + 1)
                        return FileVisitResult.CONTINUE
                    }
                    result = record(rootKey, root.relativize(file).toString(), kind, attrs.size(), mtime, now, result)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    log.warn("Cannot read {}: {}", file, exc.message)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        log.info("Scanned {}: {}", root, result)
        return result
    }

    private fun record(
        root: String,
        rel: String,
        kind: MediaKind,
        size: Long,
        mtime: Long,
        now: Long,
        acc: ScanResult,
    ): ScanResult {
        val q = db.mediaQueries
        val existing = q.selectByPath(root, rel).executeAsOneOrNull()
        return when {
            existing == null -> {
                log.debug("New {} ({})", rel, kind)
                q.insertNew(root, rel, kind, size, mtime, now)
                acc.copy(added = acc.added + 1)
            }

            existing.src_size != size || existing.src_mtime != mtime -> {
                q.requeueChanged(kind, size, mtime, now, existing.id)
                acc.copy(changed = acc.changed + 1)
            }

            else -> {
                acc.copy(unchanged = acc.unchanged + 1)
            }
        }
    }

    companion object {
        /** Syncthing metadata, temp files and other hidden entries. */
        fun isIgnoredName(name: String): Boolean =
            name.startsWith(".") || name.startsWith("~syncthing~") || name == "lost+found"
    }
}
