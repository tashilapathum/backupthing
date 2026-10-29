// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.TestFiles
import io.github.tashilapathum.backupthing.config.ConcurrencySettings
import io.github.tashilapathum.backupthing.config.ScheduleMode
import io.github.tashilapathum.backupthing.config.ScheduleSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.SettingsStore
import io.github.tashilapathum.backupthing.config.SourceSettings
import io.github.tashilapathum.backupthing.config.StagingLimit
import io.github.tashilapathum.backupthing.config.WebSettings
import io.github.tashilapathum.backupthing.db.Database
import io.github.tashilapathum.backupthing.db.MediaStatus
import io.github.tashilapathum.backupthing.pipeline.FakeEncoder
import io.github.tashilapathum.backupthing.pipeline.FakeUploader
import io.github.tashilapathum.backupthing.pipeline.Pipeline
import io.github.tashilapathum.backupthing.safety.PathGuard
import io.github.tashilapathum.backupthing.service.BackupService
import io.github.tashilapathum.backupthing.service.RunSetup
import io.github.tashilapathum.backupthing.upload.AuthStatus
import io.github.tashilapathum.backupthing.upload.FailureKind
import io.github.tashilapathum.backupthing.upload.UploadOutcome
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WebTest {
    private val base = createTempDirectory("bt-web")
    private val source =
        base.resolve("source").also {
            TestFiles.write(it, "Vids/a.jpg", TestFiles.JPEG + ByteArray(50) { 1 })
            TestFiles.write(it, "Pics/v.mp4", TestFiles.MP4 + ByteArray(50) { 2 })
        }
    private val opened = Database.open(base.resolve("state.db"))
    private val db = opened.first
    private val guard = PathGuard(base.resolve("staging"))
    private val encoder = FakeEncoder(guard)
    private val uploader = FakeUploader()
    private val configFile = base.resolve("config.toml")
    private val settings =
        Settings(
            sources = SourceSettings(roots = listOf(source.toString()), settleSeconds = 0),
            schedule = ScheduleSettings(mode = ScheduleMode.MANUAL),
            concurrency = ConcurrencySettings(maxAttempts = 1),
        )
    private val store = SettingsStore(configFile).apply { save(this@WebTest.settings) }
    private val service =
        BackupService(store, db, {
            Result.success(RunSetup(Pipeline(db, encoder, guard), uploader))
        }, stagingOf = { guard.stagingRoot })

    @AfterTest
    fun close() {
        service.stop()
        opened.second.close()
    }

    private fun ApplicationTestBuilder.app(
        configured: () -> Boolean = { true },
        security: WebSecurity = WebSecurity({ store.settings.value.web }),
    ) = application {
        backupthingModule(service, security, isConfigured = configured, saveSettings = store::save)
    }

    /** Behaves like a browser on this machine: keeps cookies, sends a Host header. */
    private fun ApplicationTestBuilder.browser(host: String = "127.0.0.1:8787"): HttpClient =
        createClient {
            install(HttpCookies)
            followRedirects = false
            defaultRequest { header(HttpHeaders.Host, host) }
        }

    private suspend fun HttpClient.csrf(page: String = "/settings"): String {
        val html = get(page).bodyAsText()
        val m = Regex("""<meta name="csrf-token" content="([^"]+)">""").find(html)
        return assertNotNull(m, "token in page").groupValues[1]
    }

    private suspend fun HttpClient.postForm(
        path: String,
        token: String?,
        vararg fields: Pair<String, String>,
    ): HttpResponse =
        submitForm(path, parameters { fields.forEach { (k, v) -> append(k, v) } }) {
            token?.let { header(WebSecurity.CSRF_HEADER, it) }
        }

    @Test
    fun `only one run at a time`() =
        runBlocking<Unit> {
            encoder.gate = CompletableDeferred()
            val first = async { service.runOnce("test") }
            encoder.started.await()
            assertEquals(null, service.runOnce("test"), "second run refused while the first is going")
            assertEquals(false, service.requestRun("test"))
            encoder.gate!!.complete(Unit)
            assertNotNull(first.await())
            val parallel = (1..5).map { async { service.runOnce("burst") } }.awaitAll()
            assertTrue(parallel.count { it != null } >= 1)
            assertEquals(1 + parallel.count { it != null }, service.recentRuns(20).size)
        }

    @Test
    fun `dashboard, fragments and APIs`() =
        testApplication {
            app()
            val client = browser()
            runBlocking { service.runOnce("test") }

            val page = client.get("/")
            assertEquals(HttpStatusCode.OK, page.status)
            val html = page.bodyAsText()
            assertTrue("Backupthing" in html && "/fragments/totals" in html && "htmx.min.js" in html)
            assertTrue("default-src 'self'" in page.headers["Content-Security-Policy"].orEmpty())
            assertEquals("DENY", page.headers["X-Frame-Options"])
            assertTrue("SameSite=Strict" in page.headers[HttpHeaders.SetCookie].orEmpty())

            assertTrue("success" in client.get("/fragments/runs").bodyAsText())
            for (name in listOf("status", "warnings", "totals", "current", "failed", "login", "deps")) {
                assertEquals(HttpStatusCode.OK, client.get("/fragments/$name").status, "fragment $name")
            }
            // The settings form must not pass its hx-target/hx-select down to the live panels inside it.
            val settingsHtml = client.get("/settings").bodyAsText()
            assertTrue("hx-disinherit=\"hx-target hx-swap hx-select hx-trigger\"" in settingsHtml)
            // hx-disinherit="*" on the form once cut buttons inside it off from a body-level CSRF header.
            assertFalse("hx-disinherit=\"*\"" in settingsHtml)
            // Every live block on every page must have a working fragment.
            val pages = listOf("/", "/settings").map { client.get(it).bodyAsText() }
            Regex(
                "hx-get=\"/fragments/([a-z]+)\"",
            ).findAll(pages.joinToString()).map { it.groupValues[1] }.toSet().forEach {
                assertEquals(HttpStatusCode.OK, client.get("/fragments/$it").status, "live block $it")
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/fragments/nope").status)
            assertEquals(HttpStatusCode.OK, client.get("/static/htmx.min.js").status)

            val status = Json.parseToJsonElement(client.get("/api/status").bodyAsText()).jsonObject
            assertEquals(2, status["totalFiles"]!!.jsonPrimitive.int)
            assertEquals("idle", status["phase"]!!.jsonPrimitive.content)
            val waybar = Json.parseToJsonElement(client.get("/api/waybar").bodyAsText()).jsonObject
            assertEquals(setOf("text", "alt", "tooltip", "class", "percentage"), waybar.keys)
            assertTrue("Backed up: 2 files" in waybar["tooltip"]!!.jsonPrimitive.content)
        }

    @Test
    fun `failed files show up and can be retried`() =
        testApplication {
            app()
            val client = browser()
            uploader.failNext("v.mp4", UploadOutcome.Failed(FailureKind.PERMANENT, "ValidationError: nope"))
            runBlocking { service.runOnce("test") }
            assertTrue("ValidationError: nope" in client.get("/fragments/failed").bodyAsText())
            assertTrue("\"class\":\"warning\"" in client.get("/api/waybar").bodyAsText())

            val id =
                db.mediaQueries
                    .selectWork(MediaStatus.FAILED)
                    .executeAsOne()
                    .id
            assertEquals(
                HttpStatusCode.Accepted,
                client
                    .post("/retry/$id") {
                        header(WebSecurity.CSRF_HEADER, client.csrf())
                    }.status,
            )
            runBlocking {
                withTimeout(10.seconds) {
                    while (db.mediaQueries
                            .selectById(id)
                            .executeAsOne()
                            .status != MediaStatus.DONE
                    ) {
                        delay(50)
                    }
                }
            }
        }

    @Test
    fun `login required shows in the API and Waybar`() =
        testApplication {
            app()
            val client = browser()
            uploader.auth = AuthStatus.Failed(FailureKind.AUTH_REQUIRED, "Failed to load session")
            runBlocking { service.runOnce("test") }
            assertTrue("\"phase\":\"login\"" in client.get("/api/status").bodyAsText())
            val waybar = client.get("/api/waybar").bodyAsText()
            assertTrue("\"text\":\"login\"" in waybar && "Login required" in waybar, waybar)
        }

    @Test
    fun `posts need a valid CSRF token and the same origin`() =
        testApplication {
            app()
            val client = browser()
            val token = client.csrf()
            assertEquals(HttpStatusCode.Forbidden, client.post("/run").status, "no token")
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/run") { header(WebSecurity.CSRF_HEADER, "forged") }.status,
            )
            val other = browser()
            other.get("/")
            assertEquals(
                HttpStatusCode.Forbidden,
                other
                    .post("/run") {
                        header(WebSecurity.CSRF_HEADER, token)
                    }.status,
                "token is per session",
            )
            val cross =
                client.post("/run") {
                    header(WebSecurity.CSRF_HEADER, token)
                    header(HttpHeaders.Origin, "http://evil.example")
                }
            assertEquals(HttpStatusCode.Forbidden, cross.status)
            val ok =
                client.post("/run") {
                    header(WebSecurity.CSRF_HEADER, token)
                    header(HttpHeaders.Origin, "http://127.0.0.1:8787")
                }
            assertTrue(ok.status == HttpStatusCode.Accepted || ok.status == HttpStatusCode.Conflict, "${ok.status}")
        }

    @Test
    fun `unknown Host headers are rejected (DNS rebinding)`() =
        testApplication {
            app()
            assertEquals(HttpStatusCode.BadRequest, browser("evil.example:8787").get("/").status)
            assertEquals(HttpStatusCode.BadRequest, browser("127.0.0.1.nip.io:8787").get("/api/status").status)
            assertEquals(HttpStatusCode.OK, browser("localhost:8787").get("/api/status").status)
            assertEquals(HttpStatusCode.OK, browser("[::1]:8787").get("/api/status").status)
        }

    @Test
    fun `origin matching normalises default ports`() {
        assertTrue(sameAuthority("http://localhost", "localhost:80"))
        assertTrue(sameAuthority("http://127.0.0.1:8787", "127.0.0.1:8787"))
        assertTrue(sameAuthority("http://[::1]:8787", "[::1]:8787"))
        assertFalse(sameAuthority("http://127.0.0.1:8788", "127.0.0.1:8787"))
        assertFalse(sameAuthority("http://evil.example", "localhost:8787"))
        assertFalse(sameAuthority("http://localhost:8787", null))
        assertFalse(sameAuthority("null", "localhost:8787"))
    }

    @Test
    fun `settings validate without saving and save valid changes`() =
        testApplication {
            app()
            val client = browser()
            val token = client.csrf()
            val before = configFile.readText()

            val invalid =
                client.postForm(
                    "/settings/validate",
                    token,
                    "web.port" to "80",
                    "schedule.intervalHours" to "abc",
                )
            val body = invalid.bodyAsText()
            assertTrue("Use a number from 1024 to 65535" in body && "Use a whole number" in body, body)
            assertEquals(before, configFile.readText())

            val rejected = client.postForm("/settings", token, "sources.roots" to "/nope/missing")
            assertTrue("Folder does not exist" in rejected.bodyAsText())
            assertEquals(before, configFile.readText())

            val saved =
                client.postForm(
                    "/settings",
                    token,
                    "schedule.mode" to "interval",
                    "schedule.intervalHours" to "12",
                    "compression.keepOriginalIfSmaller.present" to "1",
                )
            assertTrue("Settings saved." in saved.bodyAsText())
            val now = store.settings.value
            assertEquals(ScheduleMode.INTERVAL, now.schedule.mode)
            assertEquals(12, now.schedule.intervalHours)
            assertFalse(now.compression.keepOriginalIfSmaller, "unticked checkbox")
            assertEquals(settings.sources, now.sources, "fields not on the form are kept")
            assertTrue("intervalHours = 12" in configFile.readText())
        }

    @Test
    fun `first run goes through the wizard`() =
        testApplication {
            var configured = false
            app(configured = { configured })
            val client = browser()
            val home = client.get("/")
            assertEquals(HttpStatusCode.Found, home.status)
            assertEquals("/setup", home.headers[HttpHeaders.Location])
            val setup = client.get("/setup").bodyAsText()
            assertTrue("Welcome" in setup && "Source folders" in setup && "Compression" !in setup)
            assertFalse("role=\"tablist\"" in setup, "the wizard is one page of steps")

            val token = client.csrf("/setup")
            val done =
                client.postForm(
                    "/settings?wizard=1",
                    token,
                    "sources.roots" to source.toString(),
                    "schedule.mode" to "daily",
                )
            assertEquals("/", done.headers["HX-Redirect"])
            configured = true
            assertEquals(HttpStatusCode.Found, client.get("/setup").status, "wizard only while unconfigured")
        }

    @Test
    fun `about page has the note, policies and links`() =
        testApplication {
            app()
            val client = browser()
            val about = client.get("/about")
            assertEquals(HttpStatusCode.OK, about.status)
            val html = about.bodyAsText()
            assertTrue("I made Backupthing for personal use" in html)
            assertTrue("unless I get some PRs. :)" in html)
            listOf("why", "what", "contact", "support").forEach { assertTrue("id=\"$it\"" in html, "section $it") }
            // Project is the default tab; privacy, terms and license are tabs of their own.
            assertEquals(listOf("project"), shownPanels(html))
            assertEquals(4, Regex("""role="tab"""").findAll(html).count())
            listOf("privacy", "terms", "license").forEach {
                assertEquals(listOf(it), shownPanels(client.get("/about?tab=$it").bodyAsText()))
            }
            listOf("https://neomusic.tashila.me", "https://tashila.me", "mailto:tashila.pathum@gmail.com").forEach {
                assertTrue("href=\"$it\"" in html, it)
            }
            assertTrue(Regex("""href="/about"[^>]*aria-current="page"""") in html, "About tab is current")
            assertFalse(Regex("""href="/settings"[^>]*aria-current""") in html)
            assertTrue("href=\"/about\"" in client.get("/").bodyAsText(), "tab on every page")
            // Light/dark switch on every page; theme.js runs before the page is drawn (not deferred).
            assertTrue("id=\"theme-toggle\"" in html)
            assertTrue("<script src=\"/static/theme.js\"></script>" in html)
            assertEquals(HttpStatusCode.OK, client.get("/static/theme.js").status)
        }

    /** Ids of the tab panels in [html] that are not hidden. */
    private fun shownPanels(html: String) =
        Regex("""<(?:section|div)[^>]*role="tabpanel"[^>]*>""")
            .findAll(html)
            .map { it.value }
            .filter { "hidden" !in it }
            .map { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
            .toList()

    @Test
    fun `settings sections are tabs that survive form swaps`() =
        testApplication {
            app()
            val client = browser()
            val page = client.get("/settings").bodyAsText()
            assertEquals(8, Regex("""role="tab"""").findAll(page).count())
            assertEquals(listOf("sources"), shownPanels(page))
            assertEquals(listOf("uploads"), shownPanels(client.get("/settings?tab=uploads").bodyAsText()))
            assertEquals(listOf("sources"), shownPanels(client.get("/settings?tab=nope").bodyAsText()))

            val token = client.csrf()
            val validated = client.postForm("/settings/validate", token, "ui.tab" to "system").bodyAsText()
            assertEquals(listOf("system"), shownPanels(validated))
            assertTrue(Regex("""name="ui.tab"[^>]*value="system"""") in validated, "tab kept for the next request")

            // A failed save moves to the first tab with a problem and marks it.
            val failed =
                client.postForm("/settings", token, "ui.tab" to "sources", "web.port" to "80").bodyAsText()
            assertEquals(listOf("interface"), shownPanels(failed))
            assertTrue(
                Regex("""id="tab-interface"[^>]*>.*?class="tab-errors">1""", RegexOption.DOT_MATCHES_ALL) in failed,
            )
            // ...but stays put when the current tab has one too.
            val stays =
                client
                    .postForm(
                        "/settings",
                        token,
                        "ui.tab" to "uploads",
                        "web.port" to "80",
                        "concurrency.uploads" to "0",
                    ).bodyAsText()
            assertEquals(listOf("uploads"), shownPanels(stays))
        }

    @Test
    fun `network mode requires the access token`() =
        testApplication {
            val tokenValue = "0123456789abcdef0123"
            store.save(
                settings.copy(
                    web =
                        WebSettings(
                            bindAddress = "0.0.0.0",
                            accessToken = tokenValue,
                            allowedHosts = listOf("nas.lan"),
                        ),
                ),
            )
            app(security = WebSecurity({ store.settings.value.web }, trustLoopback = false))
            val client = browser("nas.lan:8787")
            assertEquals("/login", client.get("/").headers[HttpHeaders.Location])
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .get("/api/status") {
                        header(HttpHeaders.Authorization, "Bearer $tokenValue")
                    }.status,
            )

            val token = client.csrf("/login")
            assertTrue("Wrong token" in client.postForm("/login", token, "token" to "guess").bodyAsText())
            val ok = client.postForm("/login", token, "token" to tokenValue)
            assertEquals("/", ok.headers["HX-Redirect"])
            assertEquals(HttpStatusCode.OK, client.get("/").status, "auth cookie set")
        }

    @Test
    fun `folder browser lists folders only`() =
        testApplication {
            app()
            val client = browser()
            Files.createDirectories(base.resolve("browse/Camera"))
            Files.createDirectories(base.resolve("browse/.hidden"))
            Files.write(base.resolve("browse/file.jpg"), byteArrayOf(1))
            val html = client.get("/fs/dirs?path=" + base.resolve("browse")).bodyAsText()
            assertTrue("Camera" in html && "Add this folder" in html)
            assertFalse("file.jpg" in html || ".hidden" in html)
            assertTrue("Not a folder" in client.get("/fs/dirs?path=relative").bodyAsText())
        }

    @Test
    fun `reset needs typed confirmation`() =
        testApplication {
            app()
            val client = browser()
            runBlocking { service.runOnce("test") }
            val token = client.csrf()
            assertTrue("Type RESET" in client.postForm("/reset", token, "reset.confirm" to "yes").bodyAsText())
            assertEquals(
                2,
                db.mediaQueries
                    .selectWork(MediaStatus.DONE)
                    .executeAsList()
                    .size,
            )
            assertTrue("Database reset" in client.postForm("/reset", token, "reset.confirm" to "RESET").bodyAsText())
            assertEquals(
                0,
                db.mediaQueries
                    .selectWork(MediaStatus.DONE)
                    .executeAsList()
                    .size,
            )
            assertEquals(0, service.recentRuns().size)
        }

    @Test
    fun `bandwidth settings take a number and a unit`() =
        testApplication {
            app()
            val client = browser()
            val token = client.csrf()
            val page = client.get("/settings").bodyAsText()
            assertTrue("Upload speed limit" in page && "placeholder=\"No limit\"" in page, "bandwidth group renders")

            val tooSlow =
                client
                    .postForm(
                        "/settings/validate",
                        token,
                        "bandwidth.uploadLimitKBps" to "20",
                        "bandwidth.uploadLimitKBps.unit" to "kb",
                        "bandwidth.dailyCapMB" to "lots",
                        "bandwidth.dailyCapMB.unit" to "gb",
                    ).bodyAsText()
            assertTrue("Use at least 30 kB/s, or leave" in tooSlow, tooSlow)
            assertTrue("Use a number, or leave it empty for no limit" in tooSlow, tooSlow)

            client.postForm(
                "/settings",
                token,
                "bandwidth.uploadLimitKBps" to "1.5",
                "bandwidth.uploadLimitKBps.unit" to "mb",
                "bandwidth.dailyCapMB" to "",
                "bandwidth.dailyCapMB.unit" to "gb",
                "bandwidth.monthlyCapMB" to "0,25",
                "bandwidth.monthlyCapMB.unit" to "gb",
            )
            val saved = store.settings.value.bandwidth
            assertEquals(1500, saved.uploadLimitKBps)
            assertEquals(0, saved.dailyCapMB)
            assertEquals(250, saved.monthlyCapMB)
            val again = client.get("/settings").bodyAsText()
            assertTrue("value=\"1.5\"" in again, "shown in the largest whole unit")
            assertTrue("Month starts on day" in again, "reset day shows once a monthly cap is set")
        }

    @Test
    fun `staging limit switches between files, size and no limit`() =
        testApplication {
            app()
            val client = browser()
            val token = client.csrf()
            val page = client.get("/settings?tab=uploads").bodyAsText()
            assertTrue("Staging limit" in page && "Files waiting at most" in page, "files mode by default")

            val size =
                client
                    .postForm(
                        "/settings/validate",
                        token,
                        "concurrency.stagingLimit" to "size",
                        "concurrency.stagingMaxMB" to "",
                        "concurrency.stagingMaxMB.unit" to "gb",
                    ).bodyAsText()
            assertTrue("Staging space" in size && "Files waiting at most" !in size, "size mode shows the size field")
            assertTrue("Use a size above 0, or choose No limit" in size, size)

            client.postForm(
                "/settings",
                token,
                "concurrency.stagingLimit" to "size",
                "concurrency.stagingMaxMB" to "1.5",
                "concurrency.stagingMaxMB.unit" to "gb",
            )
            assertEquals(StagingLimit.SIZE, store.settings.value.concurrency.stagingLimit)
            assertEquals(1500, store.settings.value.concurrency.stagingMaxMB)

            client.postForm("/settings", token, "concurrency.stagingLimit" to "none")
            val none = client.get("/settings?tab=uploads").bodyAsText()
            assertEquals(StagingLimit.NONE, store.settings.value.concurrency.stagingLimit)
            assertTrue("Staging space" !in none && "Files waiting at most" !in none)
        }

    @Test
    fun `a capped run shows the limit on the dashboard`() =
        testApplication {
            app()
            val client = browser()
            (1..2).forEach { TestFiles.write(source, "Vids/big$it.jpg", TestFiles.JPEG + ByteArray(2_200_000) { 6 }) }
            store.save(
                settings.copy(
                    bandwidth =
                        io.github.tashilapathum.backupthing.config
                            .BandwidthSettings(dailyCapMB = 2),
                ),
            )
            val summary = assertNotNull(runBlocking { service.runOnce("test") })
            assertEquals(io.github.tashilapathum.backupthing.pipeline.RunStatus.CAPPED, summary.status)

            val html = client.get("/").bodyAsText()
            assertTrue("Daily upload cap reached" in html, html)
            assertTrue("after the cap resets at midnight" in html, html)
            assertTrue("Cap reached" in client.get("/fragments/runs").bodyAsText())
            assertTrue("used today" in client.get("/settings").bodyAsText())
            val status = Json.parseToJsonElement(client.get("/api/status").bodyAsText()).jsonObject
            assertEquals("limited", status["phase"]!!.jsonPrimitive.content)
            assertEquals(2_000_000, status["dailyCapBytes"]!!.jsonPrimitive.int)
            val waybar = Json.parseToJsonElement(client.get("/api/waybar").bodyAsText()).jsonObject
            assertEquals("limit", waybar["text"]!!.jsonPrimitive.content)
            assertTrue("Today: " in waybar["tooltip"]!!.jsonPrimitive.content)
        }
}
