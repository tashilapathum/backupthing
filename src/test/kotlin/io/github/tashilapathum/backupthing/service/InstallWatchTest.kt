// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class InstallWatchTest {
    @Test
    fun `calls back once after the jar is removed`() {
        val jar = createTempDirectory("install-watch").resolve("backupthing-1.0.0.jar").apply { writeText("x") }
        var calls = 0
        val gone = CountDownLatch(1)
        InstallWatch(jar, every = 20.milliseconds) {
            calls++
            gone.countDown()
        }.use { watch ->
            watch.start()
            assertFalse(gone.await(100, TimeUnit.MILLISECONDS), "jar still there")
            jar.deleteExisting()
            assertTrue(gone.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals(1, calls)
        }
        Files.delete(jar.parent)
    }

    @Test
    fun `no jar when running from build classes`() {
        assertNull(InstallWatch.installedJar())
    }
}
