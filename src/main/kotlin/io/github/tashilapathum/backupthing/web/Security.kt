// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.web

import io.github.tashilapathum.backupthing.config.WebSettings
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The dashboard can change which folders are read and run processes, so it is locked down even on localhost:
 * - Host allowlist (blocks DNS rebinding: a malicious page resolving its own name to 127.0.0.1)
 * - Same-origin check + CSRF token on every POST (blocks cross-site form posts)
 * - Access token login when reachable from the network; loopback clients are trusted
 * - CSP and framing/sniffing headers
 */
class WebSecurity(
    val web: () -> WebSettings,
    /** Requests from this machine skip the access token (tests turn this off to exercise LAN mode). */
    val trustLoopback: Boolean = true,
) {
    private val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray()))
    }

    fun csrfToken(session: String) = hmac("csrf:$session")

    /** Cookie value proving the access token was entered (rotates with the token and on restart). */
    fun authCookieValue(token: String) = hmac("auth:$token")

    fun allowedHosts(): Set<String> {
        val w = web()
        return (
            setOf("localhost", "127.0.0.1", "[::1]", w.bindAddress.lowercase()) +
                w.allowedHosts.map { it.lowercase() }
        ) -
            "0.0.0.0" -
            "[::]"
    }

    fun hostAllowed(hostHeader: String?): Boolean {
        if (hostHeader == null) return false
        val host = runCatching { URI("http://$hostHeader").host }.getOrNull()?.lowercase() ?: return false
        val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
        return bracketed in allowedHosts() || host in allowedHosts()
    }

    fun tokenMatches(given: String?): Boolean {
        val expected = web().accessToken
        if (expected.isEmpty() || given == null) return false
        return MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }

    companion object {
        const val SESSION_COOKIE = "bt_session"
        const val AUTH_COOKIE = "bt_auth"
        const val CSRF_HEADER = "X-CSRF-Token"
        val SessionKey = AttributeKey<String>("bt-session")

        fun newSessionId(): String =
            ByteArray(24)
                .also {
                    SecureRandom().nextBytes(it)
                }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun isLoopback(call: ApplicationCall): Boolean =
            runCatching { InetAddress.getByName(call.request.origin.remoteHost).isLoopbackAddress }.getOrDefault(false)
    }
}

/** Session id for this browser (set by [SecurityPlugin]); CSRF tokens are derived from it. */
val ApplicationCall.sessionId: String get() = attributes[WebSecurity.SessionKey]

class SecurityConfig {
    lateinit var security: WebSecurity
}

private const val CSP =
    "default-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; " +
        "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"

private val publicPaths = listOf("/login", "/static/")

val SecurityPlugin =
    createApplicationPlugin("BackupthingSecurity", ::SecurityConfig) {
        val sec = pluginConfig.security
        onCall { call ->
            val headers = call.response.headers
            headers.append("Content-Security-Policy", CSP)
            headers.append("X-Frame-Options", "DENY")
            headers.append("X-Content-Type-Options", "nosniff")
            headers.append("Referrer-Policy", "no-referrer")

            if (!sec.hostAllowed(call.request.header(HttpHeaders.Host))) {
                call.respond(HttpStatusCode.BadRequest, "Host not allowed")
                return@onCall
            }

            val session =
                call.request.cookies[WebSecurity.SESSION_COOKIE]?.takeIf { it.length in 16..64 }
                    ?: WebSecurity.newSessionId().also {
                        call.response.cookies.append(
                            Cookie(
                                WebSecurity.SESSION_COOKIE,
                                it,
                                path = "/",
                                httpOnly = true,
                                extensions =
                                    mapOf("SameSite" to "Strict"),
                            ),
                        )
                    }
            call.attributes.put(WebSecurity.SessionKey, session)

            val path = call.request.path()
            val needsLogin =
                !sec.web().isLoopbackOnly && !(sec.trustLoopback && WebSecurity.isLoopback(call)) &&
                    publicPaths.none { path == it || path.startsWith(it) }
            if (needsLogin) {
                val bearer = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                val cookieOk =
                    call.request.cookies[WebSecurity.AUTH_COOKIE] == sec.authCookieValue(sec.web().accessToken)
                if (!cookieOk && !sec.tokenMatches(bearer)) {
                    if (call.request.httpMethod == HttpMethod.Get && !path.startsWith("/api/")) {
                        call.respondRedirect("/login")
                    } else {
                        call.respond(HttpStatusCode.Unauthorized, "Login required")
                    }
                    return@onCall
                }
            }

            if (call.request.httpMethod == HttpMethod.Post) {
                val origin = call.request.header(HttpHeaders.Origin)
                if (origin != null && !sameAuthority(origin, call.request.header(HttpHeaders.Host))) {
                    call.respond(HttpStatusCode.Forbidden, "Cross-origin request rejected")
                    return@onCall
                }
                // Every form is submitted by htmx, which sends the token from <body hx-headers>.
                val header = call.request.header(WebSecurity.CSRF_HEADER)
                if (header == null ||
                    !MessageDigest.isEqual(header.toByteArray(), sec.csrfToken(session).toByteArray())
                ) {
                    call.respond(HttpStatusCode.Forbidden, "Missing or invalid security token. Reload the page")
                    return@onCall
                }
            }
        }
    }
