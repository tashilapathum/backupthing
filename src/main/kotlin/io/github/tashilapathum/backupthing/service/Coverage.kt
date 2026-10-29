// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.db.MediaStatus
import java.nio.file.Path

/** Files and original bytes in one backup state. */
data class Part(
    val files: Long = 0,
    val bytes: Long = 0,
) {
    operator fun plus(other: Part) = Part(files + other.files, bytes + other.bytes)
}

/** How far one folder (or the whole library) is backed up, by state. */
data class CoverageRow(
    val label: String,
    val done: Part = Part(),
    val ready: Part = Part(),
    val todo: Part = Part(),
    val failed: Part = Part(),
) {
    val total: Part get() = done + ready + todo + failed

    /** Backed-up share of the original bytes, rounded down so 100 means everything. */
    val percentDone: Int
        get() {
            val t = total
            return when {
                t.bytes > 0 -> (done.bytes * 100 / t.bytes).toInt()
                t.files > 0 -> (done.files * 100 / t.files).toInt()
                else -> 0
            }
        }

    operator fun plus(other: CoverageRow) =
        copy(
            done = done + other.done,
            ready = ready + other.ready,
            todo = todo + other.todo,
            failed = failed + other.failed,
        )

    fun add(
        status: MediaStatus,
        part: Part,
    ) = when (status) {
        MediaStatus.DONE -> copy(done = done + part)
        MediaStatus.ENCODED, MediaStatus.UPLOADING -> copy(ready = ready + part)
        MediaStatus.NEW, MediaStatus.ENCODING -> copy(todo = todo + part)
        MediaStatus.FAILED -> copy(failed = failed + part)
        MediaStatus.SKIPPED -> this
    }
}

/** One row of the `coverageByFolder` query. */
data class Bucket(
    val root: String,
    /** Top-level folder under [root]; empty for files directly in it. */
    val folder: String,
    val status: MediaStatus,
    val files: Long,
    val bytes: Long,
)

/** Backup progress of the whole library, and per folder: top-level folders of one source, or one row per source. */
data class Coverage(
    val total: CoverageRow,
    /** Largest first; empty when there would be only one row (it would repeat [total]). */
    val folders: List<CoverageRow>,
) {
    companion object {
        const val MAX_ROWS = 5

        fun from(
            buckets: List<Bucket>,
            roots: List<String>,
        ): Coverage {
            val configured = roots.map(::normalize)
            val labels = rootLabels(configured)
            val single = configured.size == 1
            val byRow = linkedMapOf<String, CoverageRow>()
            var total = CoverageRow("Library")
            for (b in buckets) {
                val root = normalize(b.root)
                if (root !in configured) continue
                // Keyed by the real folder, so a subfolder named like its source stays a separate row.
                val key = if (single) "/${b.folder}" else root
                val label = if (single) b.folder.ifEmpty { labels.getValue(root) } else labels.getValue(root)
                val part = Part(b.files, b.bytes)
                byRow[key] = (byRow[key] ?: CoverageRow(label)).add(b.status, part)
                total = total.add(b.status, part)
            }
            val rows = byRow.values.filter { it.total.files > 0 }.sortedByDescending { it.total.bytes }
            val folders =
                when {
                    rows.size <= 1 -> {
                        emptyList()
                    }

                    rows.size <= MAX_ROWS -> {
                        rows
                    }

                    else -> {
                        val rest = rows.drop(MAX_ROWS - 1)
                        rows.take(MAX_ROWS - 1) +
                            rest.reduce(CoverageRow::plus).copy(label = "Other (${rest.size} folders)")
                    }
                }
            return Coverage(total, folders)
        }

        private fun normalize(root: String) =
            Path
                .of(root)
                .normalize()
                .toString()

        /** The last path segment, or more of the path where two sources would otherwise share a name. */
        private fun rootLabels(roots: List<String>): Map<String, String> {
            fun tail(
                root: String,
                n: Int,
            ): String {
                val p = Path.of(root)
                val count = p.nameCount
                return if (count == 0) root else p.subpath((count - n).coerceAtLeast(0), count).toString()
            }
            return roots.associateWith { root ->
                (1..Path.of(root).nameCount.coerceAtLeast(1)).map { tail(root, it) }.firstOrNull { label ->
                    roots.none { other -> other != root && tail(other, label.count { it == '/' } + 1) == label }
                } ?: root
            }
        }
    }
}
