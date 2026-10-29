// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * A local HTTP CONNECT proxy that throttles the upload direction through [limiter]. The `proton-drive`
 * CLI has no speed limit of its own but honours `HTTPS_PROXY`; TLS stays end to end, only the encrypted
 * bytes pass through here. Listens on loopback only and tunnels to [allowedPorts] only.
 */
class ThrottleProxy(
    val limiter: RateLimiter,
    private val allowedPorts: Set<Int> = setOf(443),
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(ThrottleProxy::class.java)
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    val url: String get() = "http://127.0.0.1:${server.localPort}"

    fun start(): ThrottleProxy {
        threads.execute {
            while (!server.isClosed) {
                val client =
                    try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                threads.execute { handle(client) }
            }
        }
        log.debug("Upload throttle listening on {}", url)
        return this
    }

    override fun close() {
        server.close()
        threads.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use {
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readLine(input) ?: return
            while (readLine(input)?.isNotEmpty() == true) {
                // Headers are not needed.
            }
            val target = parseConnect(requestLine)
            if (target == null || target.second !in allowedPorts) {
                out.write(
                    "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                )
                out.flush()
                return
            }
            val upstream =
                try {
                    Socket().apply { connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MS) }
                } catch (e: Exception) {
                    log.debug("Upload throttle could not reach {}: {}", target, e.message)
                    out.write(
                        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                    )
                    out.flush()
                    return
                }
            upstream.use {
                client.soTimeout = 0
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
                val down = threads.submit { pump(upstream.getInputStream(), out, throttled = false, client) }
                pump(input, upstream.getOutputStream(), throttled = true, upstream)
                down.get()
            }
        }
    }

    /** Copies until EOF, then half-closes [to] so the other side sees the end too. */
    private fun pump(
        from: InputStream,
        to: OutputStream,
        throttled: Boolean,
        toSocket: Socket,
    ) {
        val buf = ByteArray(CHUNK)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                if (throttled) limiter.acquire(n)
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
            // Either side went away; closing below ends the other direction.
        } finally {
            runCatching { toSocket.shutdownOutput() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            when (val b = input.read()) {
                -1 -> return if (sb.isEmpty()) null else sb.toString()
                '\n'.code -> return sb.toString().trimEnd('\r')
                else -> sb.append(b.toChar())
            }
        }
        return null
    }

    companion object {
        private const val CHUNK = 16 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 30_000

        /** `CONNECT host:port HTTP/1.1` → host and port, or null. Bracketed IPv6 hosts are accepted. */
        fun parseConnect(line: String): Pair<String, Int>? {
            val parts = line.split(' ')
            if (parts.size != 3 || parts[0] != "CONNECT" || !parts[2].startsWith("HTTP/1.")) return null
            val authority = parts[1]
            val colon = authority.lastIndexOf(':')
            if (colon <= 0) return null
            val host = authority.substring(0, colon).removePrefix("[").removeSuffix("]")
            val port = authority.substring(colon + 1).toIntOrNull() ?: return null
            return if (host.isEmpty() || port !in 1..65535) null else host to port
        }
    }
}
