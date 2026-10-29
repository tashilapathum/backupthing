// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.upload

import io.github.tashilapathum.backupthing.process.ProcessResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Scripted CLI: answers by the command prefix, records every call. */
private class FakeCli(
    private val respond: (List<String>) -> ProcessResult,
) : CliRunner {
    val calls = mutableListOf<List<String>>()

    override suspend fun run(
        args: List<String>,
        timeout: kotlin.time.Duration,
    ): ProcessResult {
        calls += args
        return respond(args)
    }
}

private fun ok(stdout: String = "{}") = ProcessResult(0, stdout, "")

private fun err(stderr: String) = ProcessResult(1, "", stderr)

private const val UPLOADED = """{"transferredItems":1,"transferredBytes":10,"skippedItems":0,"failedItems":0}"""
private const val SKIPPED = """{"transferredItems":0,"transferredBytes":0,"skippedItems":1,"failedItems":0}"""

class ProtonUploaderTest {
    private val file: Path = Files.write(createTempDirectory("bt-up").resolve("a.avif"), byteArrayOf(1))

    @Test
    fun `creates the missing folder chain once, then uploads with new revisions`() =
        runBlocking {
            val existing = mutableSetOf("/my-files/Backups")
            val cli =
                FakeCli { a ->
                    when (a[1]) {
                        "info" -> if (a[3] in existing) ok() else err("Node not found: ${a[3].substringAfterLast('/')}")
                        "create-folder" -> ok().also { existing += a[3] + "/" + a[4] }
                        "upload" -> ok(UPLOADED)
                        else -> error("unexpected $a")
                    }
                }
            val up = ProtonDriveUploader(cli, "/my-files/Backups/Phone/")
            assertEquals(UploadOutcome.Uploaded(10), up.upload(file, "2026.10.25/Vids"))
            assertEquals(
                listOf(
                    listOf("filesystem", "create-folder", "--json", "/my-files/Backups", "Phone"),
                    listOf("filesystem", "create-folder", "--json", "/my-files/Backups/Phone", "2026.10.25"),
                    listOf("filesystem", "create-folder", "--json", "/my-files/Backups/Phone/2026.10.25", "Vids"),
                ),
                cli.calls.filter { it[1] == "create-folder" },
            )
            assertEquals(
                listOf(
                    "filesystem",
                    "upload",
                    "--json",
                    "-f",
                    "create-new-revision",
                    file.toString(),
                    "/my-files/Backups/Phone/2026.10.25/Vids",
                ),
                cli.calls.last(),
            )
            cli.calls.clear()
            up.upload(file, "2026.10.25/Vids")
            assertEquals(listOf("upload"), cli.calls.map { it[1] }, "folders cached")
            assertEquals("/my-files/Backups/Phone/2026.10.25/Vids/a.avif", up.remotePath("2026.10.25/Vids", "a.avif"))
            assertEquals("/my-files/Backups/Phone/a.avif", up.remotePath("", "a.avif"))
        }

    @Test
    fun `concurrent folder creation race is fine`() =
        runBlocking {
            val cli =
                FakeCli { a ->
                    when (a[1]) {
                        "info" -> err("Node not found: x")
                        "create-folder" -> err("A file or folder with that name already exists")
                        else -> ok(SKIPPED)
                    }
                }
            assertEquals(UploadOutcome.AlreadyPresent, ProtonDriveUploader(cli, "/my-files/B").upload(file, ""))
        }

    private suspend fun failureKind(cli: CliRunner) =
        (ProtonDriveUploader(cli, "/my-files/B").upload(file, "") as UploadOutcome.Failed).kind

    @Test
    fun `failures are classified`() =
        runBlocking {
            val auth = FakeCli { err("Failed to load session from secrets (ensure you have secrets available)") }
            val r = ProtonDriveUploader(auth, "/my-files/B").upload(file, "")
            assertIs<UploadOutcome.Failed>(r)
            assertEquals(FailureKind.AUTH_REQUIRED, r.kind)

            val conflict =
                FakeCli { a ->
                    if (a[1] == "upload") {
                        ProcessResult(
                            1,
                            """{"failedItems":1,"failures":[{"name":"a","error":"ValidationError: bad"}]}""",
                            "1 item(s) failed to upload",
                        )
                    } else {
                        ok()
                    }
                }
            assertEquals(FailureKind.PERMANENT, failureKind(conflict))

            // Crash output lands on stdout, stderr only has a separator.
            val offline =
                FakeCli {
                    ProcessResult(
                        1,
                        "TypeError: Unable to connect. Is the computer able to access the url?",
                        "===",
                    )
                }
            assertEquals(FailureKind.TRANSIENT, failureKind(offline))
            assertIs<AuthStatus.Failed>(ProtonPhotosUploader(offline).authStatus())

            val silent =
                FakeCli { a ->
                    if (a[1] ==
                        "upload"
                    ) {
                        ok("""{"transferredItems":0,"skippedItems":0}""")
                    } else {
                        ok()
                    }
                }
            assertEquals(FailureKind.TRANSIENT, failureKind(silent))
        }

    @Test
    fun `photos skip of an unconfirmed type is a failure, not a silent success`() =
        runBlocking {
            val dng = Files.write(file.resolveSibling("raw.dng"), byteArrayOf(1))
            val r = ProtonPhotosUploader(FakeCli { ok(SKIPPED) }).upload(dng, "")
            assertIs<UploadOutcome.Failed>(r)
            assertEquals(FailureKind.PERMANENT, r.kind)
            assertEquals(UploadOutcome.Uploaded(10), ProtonPhotosUploader(FakeCli { ok(UPLOADED) }).upload(dng, ""))
        }

    @Test
    fun `photos uploader uses skip and ignores folders`() =
        runBlocking {
            val cli = FakeCli { ok(SKIPPED) }
            val up = ProtonPhotosUploader(cli)
            assertEquals(UploadOutcome.AlreadyPresent, up.upload(file, "2026/Vids"))
            assertEquals(listOf("photo", "upload", "--json", "-c", "skip", file.toString()), cli.calls.single())
            assertEquals(AuthStatus.Ok, up.authStatus())
            assertEquals(listOf("filesystem", "list", "--json", "/"), cli.calls.last())
        }
}
