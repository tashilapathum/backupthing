// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.deps.DependencyCheck
import io.github.tashilapathum.backupthing.safety.PathGuard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Needs an NVIDIA GPU: `./gradlew test -Pgpu`. */
@Tag("gpu")
class NvencIT {
    @Test
    fun `fixture video encodes on NVENC`() =
        runBlocking<Unit> {
            val tools =
                Tools(
                    DependencyCheck.findOnPath("avifenc")!!,
                    DependencyCheck.findOnPath("ffmpeg")!!,
                    DependencyCheck.findOnPath("ffprobe")!!,
                )
            val src = Path.of(System.getProperty("fixtures.dir"), "Pics/PXL_20240601_101500123.TS.mp4")
            val encoder = MediaEncoder(tools, PathGuard(createTempDirectory("bt-nvenc")), listOf(VideoBackend.NVENC))
            val settings =
                Settings(
                    compression = CompressionSettings(videoBackend = VideoBackend.AUTO, keepOriginalIfSmaller = false),
                )
            val r = encoder.encode(src, "v.mp4", MediaKind.VIDEO, settings) {}
            assertIs<EncodeResult.Encoded>(r)
            assertEquals(VideoBackend.NVENC, r.backend)
        }
}
