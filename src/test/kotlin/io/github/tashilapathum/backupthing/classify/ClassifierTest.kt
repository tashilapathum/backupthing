// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.classify

import io.github.tashilapathum.backupthing.TestFiles
import io.github.tashilapathum.backupthing.config.SourceSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassifierTest {
    private val root = createTempDirectory("bt-classify")
    private val classifier = Classifier(SourceSettings())

    @Test
    fun `folder names are ignored - swapped Pics and Vids`() {
        assertEquals(MediaKind.VIDEO, classifier.classify(TestFiles.write(root, "Pics/a.TS.mp4", TestFiles.MP4)))
        assertEquals(MediaKind.PHOTO, classifier.classify(TestFiles.write(root, "Vids/b.jpg", TestFiles.JPEG)))
    }

    @Test
    fun `motion photo detected case-insensitively`() {
        assertEquals(MediaKind.MOTION_PHOTO, classifier.classify(TestFiles.write(root, "PXL_1.MP.jpg", TestFiles.JPEG)))
        assertEquals(MediaKind.MOTION_PHOTO, classifier.classify(TestFiles.write(root, "x.mp.JPEG", TestFiles.JPEG)))
    }

    @Test
    fun `content overrides a wrong extension`() {
        assertEquals(MediaKind.VIDEO, classifier.classify(TestFiles.write(root, "fake.jpg", TestFiles.MP4)))
        assertEquals(MediaKind.PHOTO, classifier.classify(TestFiles.write(root, "fake.mp4", TestFiles.JPEG)))
        val heicAsPhoto =
            Classifier(SourceSettings(photoExtensions = listOf("heic"), passthroughExtensions = emptyList()))
        assertEquals(MediaKind.PHOTO, heicAsPhoto.classify(TestFiles.write(root, "img.heic", TestFiles.HEIC)))
        assertEquals(MediaKind.PASSTHROUGH, classifier.classify(TestFiles.write(root, "img2.heic", TestFiles.HEIC)))
    }

    @Test
    fun `passthrough and unknown extensions`() {
        assertEquals(MediaKind.PASSTHROUGH, classifier.classify(TestFiles.write(root, "raw.DNG", byteArrayOf(1, 2))))
        assertNull(classifier.classify(TestFiles.write(root, "notes.txt", byteArrayOf(1))))
        assertNull(classifier.classify(TestFiles.write(root, "noext", TestFiles.JPEG)))
    }
}
