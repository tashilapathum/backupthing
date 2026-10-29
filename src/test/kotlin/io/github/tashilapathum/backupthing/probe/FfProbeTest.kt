// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.probe

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfProbeTest {
    // Shape of a Pixel clip: data track first, video at index 2 with a display matrix.
    private val pixel =
        """
        {"streams":[
          {"index":0,"codec_type":"data","duration":"10.7"},
          {"index":1,"codec_type":"audio","codec_name":"aac","duration":"10.67"},
          {"index":2,"codec_type":"video","codec_name":"hevc","width":1920,"height":1080,"pix_fmt":"yuvj420p",
           "color_range":"pc","color_space":"bt709","color_transfer":"bt709","color_primaries":"bt709","duration":"10.711700",
           "side_data_list":[{"side_data_type":"Display Matrix","rotation":-90}]}
        ],"format":{"duration":"10.72","tags":{"creation_time":"2025-01-13T08:40:21.000000Z"}}}
        """.trimIndent()

    @Test
    fun `parses first video stream, rotation, colour and creation time`() {
        val i = FfProbe.parse(pixel)
        assertEquals(1920, i.width)
        assertEquals(1080, i.height)
        assertEquals(-90, i.rotation)
        assertEquals(10.7117, i.durationSeconds)
        assertEquals("hevc", i.videoCodec)
        assertEquals("pc", i.colorRange)
        assertEquals("aac", i.audioCodec)
        assertEquals(Instant.parse("2025-01-13T08:40:21Z"), i.creationTime)
        assertFalse(i.is10Bit)
    }

    @Test
    fun `image without audio, duration or tags`() {
        val i =
            FfProbe.parse(
                """{"streams":[{"codec_type":"video","codec_name":"mjpeg","width":3024,"height":4032,"pix_fmt":"yuvj420p"}],"format":{}}""",
            )
        assertEquals(0, i.rotation)
        assertNull(i.audioCodec)
        assertNull(i.creationTime)
        assertNull(i.durationSeconds)
    }

    @Test
    fun `10-bit detection and missing video`() {
        val i = FfProbe.parse("""{"streams":[{"codec_type":"video","width":2,"height":2,"pix_fmt":"yuv420p10le"}]}""")
        assertTrue(i.is10Bit)
        assertFailsWith<ProbeException> { FfProbe.parse("""{"streams":[{"codec_type":"audio"}]}""") }
    }
}
