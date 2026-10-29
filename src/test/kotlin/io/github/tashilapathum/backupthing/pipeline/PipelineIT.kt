// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.pipeline

import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.db.Database
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.encode.MediaEncoder
import io.github.tashilapathum.backupthing.encode.Tools
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.util.FileUtil
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Full run with the real encoders (SVT-AV1) on synthetic fixtures and an in-memory remote. */
class PipelineIT {
    @Test
    fun `end to end on fixtures`() =
        runBlocking<Unit> {
            val fixtures = System.getProperty("fixtures.dir")?.let(Path::of)
            assumeTrue(fixtures != null && Files.isDirectory(fixtures), "fixtures not generated")
            val found = listOf("avifenc", "ffmpeg", "ffprobe").map { DependencyCheck.findOnPath(it) }
            assumeTrue(found.all { it != null }, "media tools missing")

            val base = createTempDirectory("bt-pipe-it")
            val source = base.resolve("source")
            Files.walk(fixtures!!).use { s ->
                s.forEach { p ->
                    val t = source.resolve(fixtures.relativize(p).toString())
                    if (Files.isDirectory(p)) Files.createDirectories(t) else Files.copy(p, t)
                }
            }

            fun tree() =
                Files.walk(source).use { s ->
                    s.filter { Files.isRegularFile(it) }.toList().associate {
                        it.toString() to
                            FileUtil.sha256(it)
                    }
                }
            val before = tree()

            val (db, driver) = Database.open(base.resolve("state.db"))
            driver.use {
                val guard = PathGuard(base.resolve("staging"))
                val tools = Tools(found[0]!!, found[1]!!, found[2]!!)
                val pipeline = Pipeline(db, MediaEncoder(tools, guard, listOf(VideoBackend.SVT_AV1)), guard)
                val uploader = FakeUploader()
                val settings =
                    Settings(
                        sources = SourceSettings(roots = listOf(source.toString()), settleSeconds = 0),
                        compression = CompressionSettings(videoBackend = VideoBackend.AUTO),
                    )

                val first = pipeline.run(settings, uploader, "test")
                assertEquals(RunStatus.SUCCESS, first.status)
                assertEquals(3, first.uploaded)
                assertEquals(
                    setOf(
                        "/r/Vids/PXL_20240602_183012456.avif",
                        "/r/Vids/PXL_20240603_074530789.avif",
                        "/r/Pics/PXL_20240601_101500123.TS.mp4",
                    ),
                    uploader.remote.keys,
                )
                assertEquals(
                    3,
                    db.mediaQueries
                        .selectWork(MediaStatus.DONE)
                        .executeAsList()
                        .size,
                )
                assertEquals(
                    0L,
                    Files.walk(guard.stagingRoot).use { s ->
                        s.filter { Files.isRegularFile(it) }.count()
                    },
                )
                assertEquals(before, tree())

                val second = pipeline.run(settings, uploader, "test")
                assertEquals(0, second.encoded + second.keptOriginal + second.uploaded)
            }
        }
}
