// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.MotionPhotoMode
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SystemSettings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.probe.FfProbe
import io.github.tashilapathum.backupthing.process.ProcessRunner
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.util.FileUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs the real tools on synthetic fixtures (`./gradlew generateFixtures`). CPU encoder only, so it works in CI. */
class MediaEncoderIT {
    private lateinit var tools: Tools
    private lateinit var source: Path
    private lateinit var guard: PathGuard
    private val settings = Settings(compression = CompressionSettings(videoBackend = VideoBackend.SVT_AV1))

    @BeforeTest
    fun setUp() {
        val fixtures = System.getProperty("fixtures.dir")?.let(Path::of)
        assumeTrue(fixtures != null && Files.isDirectory(fixtures), "fixtures not generated")
        val found = listOf("avifenc", "ffmpeg", "ffprobe").map { DependencyCheck.findOnPath(it) }
        assumeTrue(found.all { it != null }, "media tools missing")
        tools = Tools(found[0]!!, found[1]!!, found[2]!!)
        val base = createTempDirectory("bt-encode")
        source = base.resolve("source")
        Files.walk(fixtures!!).use { s ->
            s.forEach { p ->
                val target = source.resolve(fixtures.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target)
            }
        }
        guard = PathGuard(base.resolve("staging"))
    }

    private fun encoder(backends: List<VideoBackend> = listOf(VideoBackend.SVT_AV1)) =
        MediaEncoder(tools, guard, backends)

    private fun treeHash(): Map<String, String> =
        Files.walk(source).use { s ->
            s.filter { Files.isRegularFile(it) }.toList().associate {
                source.relativize(it).toString() to
                    FileUtil.sha256(it)
            }
        }

    private fun bytesContain(
        file: Path,
        text: String,
    ) = String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(text)

    @Test
    fun `photo becomes AVIF with EXIF and source mtime, source untouched`() =
        runBlocking<Unit> {
            val before = treeHash()
            val rel = "Vids/PXL_20240602_183012456.jpg"
            val r = encoder().encode(source.resolve(rel), rel, MediaKind.PHOTO, settings) {}
            assertIs<EncodeResult.Encoded>(r)
            assertEquals("PXL_20240602_183012456.avif", r.output.name)
            assertTrue(r.output.startsWith(guard.stagingRoot))
            assertTrue(bytesContain(r.output, "2024:06:02 18:30:12"), "EXIF DateTimeOriginal kept")
            assertEquals(Files.getLastModifiedTime(source.resolve(rel)), Files.getLastModifiedTime(r.output))
            assertEquals(FileUtil.sha256(r.output), r.sha256)
            assertEquals(before, treeHash())
        }

    @Test
    fun `motion photo still-only drops XMP, as-is keeps original`() =
        runBlocking<Unit> {
            val rel = "Vids/PXL_20240603_074530789.MP.jpg"
            val r = encoder().encode(source.resolve(rel), rel, MediaKind.MOTION_PHOTO, settings) {}
            assertIs<EncodeResult.Encoded>(r)
            assertEquals("PXL_20240603_074530789.avif", r.output.name)
            assertFalse(bytesContain(r.output, "ftyp" + "isom"), "embedded clip not carried")
            val asIs = settings.copy(compression = settings.compression.copy(motionPhotoMode = MotionPhotoMode.AS_IS))
            assertIs<EncodeResult.UseOriginal>(
                encoder().encode(source.resolve(rel), rel, MediaKind.MOTION_PHOTO, asIs) {},
            )
        }

    @Test
    fun `video becomes AV1 with audio, metadata, duration and creation-time mtime`() =
        runBlocking<Unit> {
            val rel = "Pics/PXL_20240601_101500123.TS.mp4"
            var lastProgress = 0.0
            val r = encoder().encode(source.resolve(rel), rel, MediaKind.VIDEO, settings) { lastProgress = it }
            assertIs<EncodeResult.Encoded>(r)
            assertEquals(VideoBackend.SVT_AV1, r.backend)
            assertEquals(1.0, lastProgress)
            val out = FfProbe(tools.ffprobe).probe(r.output)
            assertEquals("av1", out.videoCodec)
            assertEquals("aac", out.audioCodec)
            assertEquals("pc", out.colorRange)
            assertEquals(Instant.parse("2024-06-01T10:15:00Z"), out.creationTime)
            assertEquals(Instant.parse("2024-06-01T10:15:00Z"), Files.getLastModifiedTime(r.output).toInstant())
            val tags =
                ProcessRunner.run(
                    listOf(
                        tools.ffprobe.toString(),
                        "-v",
                        "error",
                        "-show_entries",
                        "format_tags=location",
                        r.output.toString(),
                    ),
                )
            assertTrue("+06.9271+079.8612/" in tags.stdout, tags.stdout)
        }

    @Test
    fun `failed hardware backend falls back to SVT-AV1`() =
        runBlocking<Unit> {
            // Claim QSV works although it does not on this machine (no Intel GPU here or in CI).
            val working = DependencyCheck(SystemSettings()).probeVideoBackends(tools.ffmpeg)
            assumeFalse(VideoBackend.QSV in working, "QSV really works here")
            val compression = settings.compression.copy(videoBackend = VideoBackend.QSV, keepOriginalIfSmaller = false)
            val qsv = settings.copy(compression = compression)
            val rel = "Pics/PXL_20240601_101500123.TS.mp4"
            val enc = encoder(listOf(VideoBackend.QSV, VideoBackend.SVT_AV1))
            val r = enc.encode(source.resolve(rel), rel, MediaKind.VIDEO, qsv) {}
            assertIs<EncodeResult.Encoded>(r)
            assertEquals(VideoBackend.SVT_AV1, r.backend)
        }

    @Test
    fun `keeps original when the compressed file is not smaller`() =
        runBlocking<Unit> {
            val lossless = settings.copy(compression = settings.compression.copy(photoQuality = 100, photoSpeed = 10))
            val rel = "Vids/PXL_20240602_183012456.jpg"
            val r = encoder().encode(source.resolve(rel), rel, MediaKind.PHOTO, lossless) {}
            assertIs<EncodeResult.UseOriginal>(r)
            assertEquals(0L, Files.walk(guard.stagingRoot).use { s -> s.filter { Files.isRegularFile(it) }.count() })
        }

    @Test
    fun `passthrough never touches staging`() =
        runBlocking<Unit> {
            assertIs<EncodeResult.UseOriginal>(
                encoder().encode(source.resolve("x.dng"), "x.dng", MediaKind.PASSTHROUGH, settings) {},
            )
        }

    @Test
    fun `cancelling an encode kills ffmpeg and removes the partial file`() =
        runBlocking<Unit> {
            val long = guard.stagingRoot.resolveSibling("long.mp4")
            val gen =
                ProcessRunner.run(
                    listOf(
                        tools.ffmpeg.toString(),
                        "-v",
                        "error",
                        "-y",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=size=1920x1080:rate=30:duration=60",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "ultrafast",
                        long.toString(),
                    ),
                    timeout = 2.minutes,
                )
            assertTrue(gen.ok, gen.stderr)
            val started = CompletableDeferred<Unit>()
            val job =
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    encoder().encode(long, "long.mp4", MediaKind.VIDEO, settings) { if (it > 0) started.complete(Unit) }
                }
            withTimeout(60.seconds) { started.await() }
            job.cancelAndJoin()
            val ffmpegChildren =
                ProcessHandle
                    .current()
                    .descendants()
                    .filter {
                        it
                            .info()
                            .command()
                            .orElse("")
                            .endsWith("ffmpeg")
                    }.count()
            assertEquals(0, ffmpegChildren)
            assertEquals(0L, Files.walk(guard.stagingRoot).use { s -> s.filter { Files.isRegularFile(it) }.count() })
        }
}
