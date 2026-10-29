// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliOutputTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/proton-cli/$name")).readText()

    @Test
    fun `parses upload summaries`() {
        assertEquals(UploadSummary(1, 444799), CliOutput.parseSummary(fixture("upload-ok.json")))
        assertEquals(1, CliOutput.parseSummary(fixture("upload-skipped-identical.json"))?.skippedItems)
        val conflict = CliOutput.parseSummary(fixture("upload-name-conflict.json") + "\n1 item(s) failed to upload\n")
        assertEquals(1, conflict?.failedItems)
        assertTrue(
            conflict!!
                .failures
                .single()
                .error
                .startsWith("ValidationError"),
        )
        assertNull(CliOutput.parseSummary("not json"))
        assertNull(CliOutput.parseSummary(""))
    }

    @Test
    fun `classifies observed errors`() {
        assertEquals(FailureKind.AUTH_REQUIRED, CliOutput.classify(fixture("stderr-auth.txt")))
        assertEquals(
            FailureKind.TRANSIENT,
            CliOutput.classify(fixture("stderr-network.txt") + fixture("stdout-network.txt")),
        )
        assertEquals(
            FailureKind.PERMANENT,
            CliOutput.classify(
                "1 item(s) failed to upload",
                CliOutput.parseSummary(fixture("upload-name-conflict.json")),
            ),
        )
        assertEquals(FailureKind.TRANSIENT, CliOutput.classify("something unexpected"))
    }

    @Test
    fun `short error drops stack traces`() {
        val e = CliOutput.shortError(fixture("stderr-network.txt") + "\n" + fixture("stdout-network.txt"))
        assertTrue(e.startsWith("TypeError: Unable to connect"), e)
        assertFalse("at " in e.substringAfter("connect"))
        assertEquals(
            "a.jpg: ValidationError: x",
            CliOutput.shortError("", UploadSummary(failures = listOf(UploadFailure("a.jpg", "ValidationError: x")))),
        )
    }
}
