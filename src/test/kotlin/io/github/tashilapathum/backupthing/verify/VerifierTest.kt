// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.verify

import io.github.tashilapathum.backupthing.probe.MediaInfo
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerifierTest {
    private val dir = createTempDirectory("bt-verify")
    private val out = Files.write(dir.resolve("o.mp4"), byteArrayOf(1, 2, 3))
    private val src = MediaInfo(1920, 1080, -90, 10.0, "hevc", "yuvj420p", "pc", null, null, null, "aac", null)

    @Test
    fun `accepts matching output within duration tolerance`() {
        Verifier.verify(src, src.copy(videoCodec = "av1", durationSeconds = 10.4), out, isVideo = true)
    }

    @Test
    fun `rejects size, rotation, duration, audio and empty output`() {
        assertFailsWith<VerifyException> { Verifier.verify(src, src.copy(width = 1080, height = 1920), out, true) }
        assertFailsWith<VerifyException> { Verifier.verify(src, src.copy(rotation = 0), out, true) }
        assertFailsWith<VerifyException> { Verifier.verify(src, src.copy(durationSeconds = 9.0), out, true) }
        assertFailsWith<VerifyException> { Verifier.verify(src, src.copy(audioCodec = null), out, true) }
        val empty = Files.write(dir.resolve("e.avif"), ByteArray(0))
        assertFailsWith<VerifyException> { Verifier.verify(src, src, empty, false) }
    }

    @Test
    fun `not smaller compares file sizes`() {
        val big = Files.write(dir.resolve("big"), ByteArray(10))
        assertTrue(Verifier.notSmaller(out, big))
        assertTrue(!Verifier.notSmaller(big, out))
    }
}
