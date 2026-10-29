// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import io.github.tashilapathum.backupthing.TestFiles
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SourceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ServiceExtrasTest {
    @Test
    fun `login shows the sign-in link and reports success`() =
        runBlocking<Unit> {
            val scope = CoroutineScope(Dispatchers.Default)
            var succeeded = false
            val login = ProtonLogin(scope, onSuccess = { succeeded = true })
            val fake =
                listOf(
                    "sh",
                    "-c",
                    "echo 'Sign in in your browser.'; " +
                        "echo 'https://account.proton.me/desktop/login#payload=x'; sleep 1",
                )
            login.start(fake, emptyMap())
            withTimeout(5.seconds) { while (login.state.value !is LoginState.Waiting) delay(20) }
            assertEquals(
                "https://account.proton.me/desktop/login#payload=x",
                (login.state.value as LoginState.Waiting).url,
            )
            withTimeout(5.seconds) { while (login.state.value != LoginState.Succeeded) delay(20) }
            assertTrue(succeeded)

            login.start(listOf("sh", "-c", "echo 'Error: cancelled by user' >&2; exit 3"), emptyMap())
            withTimeout(5.seconds) { while (login.state.value !is LoginState.Failed) delay(20) }
            assertEquals("Error: cancelled by user", (login.state.value as LoginState.Failed).message)
            scope.cancel()
        }

    @Test
    fun `sample test only accepts files in the source folders`() =
        runBlocking<Unit> {
            val base = createTempDirectory("bt-sample")
            val inside = TestFiles.write(base.resolve("src"), "a.jpg", TestFiles.JPEG)
            val outside = TestFiles.write(base.resolve("elsewhere"), "b.jpg", TestFiles.JPEG)
            val settings = Settings(sources = SourceSettings(roots = listOf(base.resolve("src").toString())))
            val tester = SampleTester(base.resolve("samples"))
            val r = tester.test(outside, settings)
            assertIs<IllegalArgumentException>(r.exceptionOrNull())
            assertTrue(tester.test(base.resolve("src/../elsewhere/b.jpg"), settings).isFailure, "no ../ escape")
            assertEquals(null, tester.previewFile("../../etc", "before"))
            assertTrue(inside.toFile().exists())
        }
}
