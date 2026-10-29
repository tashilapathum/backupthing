// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.config.WebSettings
import io.github.tashilapathum.backupthing.service.BackupService
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val json = Json { encodeDefaults = true }

/** True when the Origin header names the same host and port as the Host header (default ports normalised). */
internal fun sameAuthority(
    origin: String,
    hostHeader: String?,
): Boolean {
    if (hostHeader == null) return false
    val uri = runCatching { URI(origin) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val defaultPort = if (scheme == "https") 443 else 80
    val originHost = uri.host?.lowercase() ?: return false
    val originPort = if (uri.port == -1) defaultPort else uri.port
    val hostUri = runCatching { URI("$scheme://$hostHeader") }.getOrNull() ?: return false
    val host = hostUri.host?.lowercase() ?: return false
    val port = if (hostUri.port == -1) defaultPort else hostUri.port
    return host == originHost && port == originPort
}

/** Fires at most every [period] while [source] keeps changing, always delivering the latest change. */
private fun <T> Flow<T>.throttleLatest(period: kotlin.time.Duration): Flow<T> =
    conflate().transform {
        emit(it)
        delay(period)
    }

fun Application.backupthingModule(
    service: BackupService,
    security: WebSecurity = WebSecurity({ service.settings.web }),
    /** Interface settings the server is actually running with. */
    runningWeb: WebSettings = service.settings.web,
    isConfigured: () -> Boolean = { true },
    saveSettings: (Settings) -> Unit = {},
) {
    val log = LoggerFactory.getLogger("web")
    install(SSE)
    install(SecurityPlugin) { this.security = security }
    install(StatusPages) {
        // A browser closing the live-update stream mid-write; nothing to report.
        exception<io.ktor.util.cio.ChannelWriteException> {
            _,
            cause,
            ->
            log.debug("Client went away: {}", cause.message)
        }
        // Server shutting down while a live-update stream is open.
        exception<kotlinx.coroutines.CancellationException> { _, _ -> }
        exception<Throwable> { call, cause ->
            log.error("Request failed", cause)
            call.respondText("Internal error: ${cause.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    fun ApplicationCall.csrf() = security.csrfToken(sessionId)

    fun formContext(
        errors: List<io.github.tashilapathum.backupthing.config.ValidationError> = emptyList(),
        notice: String? = null,
        wizard: Boolean,
        tab: String? = null,
    ) = FormContext(service, errors, notice, runningWeb, wizard, tab)

    routing {
        staticResources("/static", "static")

        get("/") {
            if (!isConfigured()) return@get call.respondRedirect("/setup")
            call.respondHtml { Pages.dashboard(this, service, call.csrf()) }
        }
        get("/setup") {
            if (isConfigured()) return@get call.respondRedirect("/")
            call.respondHtml {
                SettingsPages.setupPage(
                    this,
                    service.settings,
                    formContext(wizard = true),
                    call.csrf(),
                )
            }
        }
        get("/settings") {
            call.respondHtml {
                SettingsPages.settingsPage(
                    this,
                    service.settings,
                    formContext(wizard = false, tab = call.request.queryParameters["tab"]),
                    call.csrf(),
                )
            }
        }
        get("/about") {
            call.respondHtml { AboutPage.page(this, service, call.csrf(), call.request.queryParameters["tab"]) }
        }
        post("/settings/validate") {
            val wizard = call.request.queryParameters["wizard"] != null
            val params = call.receiveParameters()
            val (settings, errors) = SettingsForm(params, service.settings).parse()
            call.respondText(
                SettingsPages.formFragment(settings, formContext(errors, wizard = wizard, tab = params["ui.tab"])),
                ContentType.Text.Html,
            )
        }
        post("/settings") {
            val wizard = call.request.queryParameters["wizard"] != null
            val params = call.receiveParameters()
            val (settings, errors) = SettingsForm(params, service.settings).parse()
            if (errors.isNotEmpty()) {
                val tab = SettingsPages.tabForErrors(params["ui.tab"], errors)
                return@post call.respondText(
                    SettingsPages.formFragment(settings, formContext(errors, wizard = wizard, tab = tab)),
                    ContentType.Text.Html,
                )
            }
            saveSettings(settings)
            if (wizard) {
                call.response.headers.append("HX-Redirect", "/")
                call.respond(HttpStatusCode.OK, "")
            } else {
                call.respondText(
                    SettingsPages.formFragment(
                        settings,
                        formContext(notice = "Settings saved.", wizard = false, tab = params["ui.tab"]),
                    ),
                    ContentType.Text.Html,
                )
            }
        }

        get("/fs/dirs") {
            val raw = call.request.queryParameters["path"].orEmpty()
            val path = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
            if (path == null || !path.isAbsolute || !Files.isDirectory(path)) {
                return@get call.respondText("<p class=\"error\">Not a folder</p>", ContentType.Text.Html)
            }
            call.respondText(SettingsPages.dirBrowser(path), ContentType.Text.Html)
        }

        post("/auth/login") {
            call.respond(if (service.startLogin()) HttpStatusCode.Accepted else HttpStatusCode.Conflict)
        }
        post("/auth/login/cancel") {
            service.login.cancel()
            call.respond(HttpStatusCode.NoContent)
        }
        post("/deps/check") {
            service.refreshDeps()
            call.respond(HttpStatusCode.NoContent)
        }

        post("/sample") {
            val params = call.receiveParameters()
            val (settings, _) = SettingsForm(params, service.settings).parse()
            val path = params["sample.path"]?.takeIf { it.isNotBlank() }
            val result =
                if (path ==
                    null
                ) {
                    Result.failure(IllegalArgumentException("Pick a file first"))
                } else {
                    service.samples.test(Path.of(path), settings)
                }
            call.respondText(SettingsPages.sampleResult(result), ContentType.Text.Html)
        }
        get("/sample/{id}/{which}") {
            val file = service.samples.previewFile(call.parameters["id"].orEmpty(), call.parameters["which"].orEmpty())
            if (file == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(file.toFile())
        }

        post("/reset") {
            val confirmed = call.receiveParameters()["reset.confirm"] == "RESET"
            val message =
                when {
                    !confirmed -> "Type RESET to confirm."
                    service.resetDatabase() -> "Database reset."
                    else -> "A run is in progress. Try again when it finishes."
                }
            call.respondText(message)
        }

        get("/login") { call.respondHtml { SettingsPages.loginPage(this, call.csrf(), null) } }
        post("/login") {
            val token = call.receiveParameters()["token"]
            if (!security.tokenMatches(token)) {
                delay(700) // slows down guessing
                return@post call.respondText("Wrong token.", ContentType.Text.Plain)
            }
            call.response.cookies.append(
                Cookie(
                    WebSecurity.AUTH_COOKIE,
                    security.authCookieValue(service.settings.web.accessToken),
                    path = "/",
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.response.headers.append("HX-Redirect", "/")
            call.respond(HttpStatusCode.OK, "")
        }

        get("/fragments/{name}") {
            val html = Pages.fragment(call.parameters["name"].orEmpty(), service)
            if (html == null) call.respond(HttpStatusCode.NotFound) else call.respondText(html, ContentType.Text.Html)
        }

        sse("/events") {
            val changes: Flow<Unit> =
                merge(
                    service.pipelineState.map { },
                    service.events.map { },
                    service.warnings.map { },
                    service.nextRun.map { },
                    service.auth.map { },
                    service.login.state.map { },
                    service.deps.map { },
                    flow {
                        while (true) {
                            delay(30.seconds)
                            emit(Unit)
                        }
                    },
                ).throttleLatest(500.milliseconds)
            changes.collect { send(ServerSentEvent(data = "update", event = "update")) }
        }

        post("/run") {
            call.respond(if (service.requestRun("web")) HttpStatusCode.Accepted else HttpStatusCode.Conflict)
        }
        post("/retry-failed") {
            service.retryFailed()
            call.respond(HttpStatusCode.Accepted)
        }
        post("/retry/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            service.retry(id)
            call.respond(HttpStatusCode.Accepted)
        }

        get("/api/status") {
            call.respondText(
                json.encodeToString(StatusDto.serializer(), Snapshot(service).toStatus()),
                ContentType.Application.Json,
            )
        }
        get("/api/waybar") {
            call.respondText(
                json.encodeToString(WaybarDto.serializer(), Snapshot(service).toWaybar()),
                ContentType.Application.Json,
            )
        }
    }
}

fun startServer(
    service: BackupService,
    host: String,
    port: Int,
    isConfigured: () -> Boolean,
    saveSettings: (Settings) -> Unit,
): EmbeddedServer<*, *> {
    val running = service.settings.web
    return embeddedServer(CIO, host = host, port = port) {
        backupthingModule(service, runningWeb = running, isConfigured = isConfigured, saveSettings = saveSettings)
    }.start(wait = false)
}
