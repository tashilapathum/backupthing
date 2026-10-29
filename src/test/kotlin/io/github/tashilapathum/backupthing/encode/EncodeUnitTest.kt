// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.encode

import io.github.tashilapathum.backupthing.classify.MediaKind
import io.github.tashilapathum.backupthing.config.CompressionSettings
import io.github.tashilapathum.backupthing.config.VideoBackend
import io.github.tashilapathum.backupthing.probe.MediaInfo
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncodeUnitTest {
    private val info =
        MediaInfo(1920, 1080, -90, 10.0, "hevc", "yuvj420p", "pc", "bt709", "bt709", "bt709", "aac", null)

    private fun cmd(
        backend: VideoBackend,
        i: MediaInfo = info,
    ) = VideoEncoder.command(
        Path.of("ffmpeg"),
        Path.of("in.mp4"),
        Path.of("out.mp4"),
        i,
        backend,
        CompressionSettings(),
    )

    private fun List<String>.after(flag: String) = this[indexOf(flag) + 1]

    @Test
    fun `progress parser`() {
        val seen = mutableListOf<Double>()
        val p = FfmpegProgress(10.0) { seen += it }
        listOf(
            "frame=10",
            "out_time_us=2500000",
            "progress=continue",
            "out_time_ms=20000000",
            "out_time_us=N/A",
            "progress=end",
        ).forEach(p::onLine)
        assertEquals(listOf(0.25, 1.0, 1.0), seen)
        val none = mutableListOf<Double>()
        FfmpegProgress(null) { none += it }.onLine("out_time_us=1000")
        assertTrue(none.isEmpty())
    }

    @Test
    fun `output naming avoids collisions`() {
        assertEquals("2026/Vids/a.avif", OutputNaming.outputRelPath("2026/Vids/a.jpg", MediaKind.PHOTO))
        assertEquals("a.avif", OutputNaming.outputRelPath("a.JPEG", MediaKind.PHOTO))
        assertEquals("a.png.avif", OutputNaming.outputRelPath("a.png", MediaKind.PHOTO))
        assertEquals("PXL_1.avif", OutputNaming.outputRelPath("PXL_1.MP.jpg", MediaKind.MOTION_PHOTO))
        assertEquals("x/PXL_1.TS.mp4", OutputNaming.outputRelPath("x/PXL_1.TS.mp4", MediaKind.VIDEO))
        assertEquals("clip.mov.mp4", OutputNaming.outputRelPath("clip.mov", MediaKind.VIDEO))
    }

    @Test
    fun `common video args keep rotation, range, colour, audio and metadata`() {
        val c = cmd(VideoBackend.NVENC)
        assertTrue("-noautorotate" in c && c.indexOf("-noautorotate") < c.indexOf("-i"))
        assertEquals("0:v:0", c[c.indexOf("-map") + 1])
        assertTrue("0:a:0?" in c)
        assertEquals("pc", c.after("-color_range"))
        assertEquals("bt709", c.after("-color_trc"))
        assertEquals("copy", c.after("-c:a"))
        assertEquals("0", c.after("-map_metadata"))
        assertEquals("pipe:1", c.after("-progress"))
        assertEquals("out.mp4", c.last())
    }

    @Test
    fun `per backend codec and quality`() {
        val s = CompressionSettings()
        assertEquals(s.nvencCq.toString(), cmd(VideoBackend.NVENC).after("-cq"))
        assertEquals("av1_nvenc", cmd(VideoBackend.NVENC).after("-c:v"))
        assertEquals(s.svtAv1Crf.toString(), cmd(VideoBackend.SVT_AV1).after("-crf"))
        assertEquals("libsvtav1", cmd(VideoBackend.SVT_AV1).after("-c:v"))
        val vaapi = cmd(VideoBackend.VAAPI)
        assertEquals("/dev/dri/renderD128", vaapi.after("-vaapi_device"))
        assertEquals("format=nv12,hwupload", vaapi.after("-vf"))
        assertEquals(s.vaapiQp.toString(), vaapi.after("-qp"))
        assertEquals(s.qsvQuality.toString(), cmd(VideoBackend.QSV).after("-global_quality"))
        assertEquals("nv12", cmd(VideoBackend.QSV).after("-pix_fmt"))
    }

    @Test
    fun `10-bit sources stay 10-bit and odd audio is re-encoded`() {
        val hdr =
            info.copy(
                pixFmt = "yuv420p10le",
                colorTransfer = "arib-std-b67",
                colorRange = "tv",
                audioCodec = "pcm_s16le",
            )
        assertEquals("p010le", cmd(VideoBackend.NVENC, hdr).after("-pix_fmt"))
        assertEquals("yuv420p10le", cmd(VideoBackend.SVT_AV1, hdr).after("-pix_fmt"))
        assertEquals("format=p010,hwupload", cmd(VideoBackend.VAAPI, hdr).after("-vf"))
        assertEquals("arib-std-b67", cmd(VideoBackend.SVT_AV1, hdr).after("-color_trc"))
        assertFalse("-color_range" in cmd(VideoBackend.SVT_AV1, hdr))
        assertEquals("aac", cmd(VideoBackend.SVT_AV1, hdr).after("-c:a"))
        val silent = cmd(VideoBackend.SVT_AV1, info.copy(audioCodec = null, colorPrimaries = "unknown"))
        assertFalse("-c:a" in silent)
        assertFalse("-color_primaries" in silent)
    }

    @Test
    fun `avifenc args`() {
        val s = CompressionSettings(photoQuality = 70, photoSpeed = 6)
        val c =
            ImageEncoder.command(
                Path.of("avifenc"),
                Path.of("a.jpg"),
                Path.of("a.avif"),
                s,
                dropXmp = true,
                jobs = 0,
            )
        assertEquals(listOf("avifenc", "-q", "70", "-s", "6", "-j", "1", "--ignore-xmp", "a.jpg", "a.avif"), c)
        assertFalse("--ignore-xmp" in ImageEncoder.command(Path.of("avifenc"), Path.of("a"), Path.of("b"), s, false, 4))
    }
}
