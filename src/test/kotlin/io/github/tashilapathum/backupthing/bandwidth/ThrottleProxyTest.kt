// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThrottleProxyTest {
    /** Stands in for the Proton server: reads everything, answers with its SHA-256. */
    private val sink = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sinkThread =
        thread(isDaemon = true) {
            while (!sink.isClosed) {
                val s = runCatching { sink.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = it.getInputStream().read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                        }
                        it.getOutputStream().write(digest.digest())
                    }
                }
            }
        }
    private val limiter = RateLimiter()
    private val proxy = ThrottleProxy(limiter, allowedPorts = setOf(sink.localPort)).start()
    private val proxyPort = proxy.url.substringAfterLast(':').toInt()

    @AfterTest
    fun close() {
        proxy.close()
        sink.close()
    }

    private fun Socket.readStatusLine(): String {
        val sb = StringBuilder()
        var crlfs = 0
        while (crlfs < 2) {
            val c = getInputStream().read()
            if (c < 0) break
            if (c == '\n'.code) {
                crlfs++
            } else if (c != '\r'.code) {
                crlfs = 0
            }
            sb.append(c.toChar())
        }
        return sb.lineSequence().first()
    }

    private fun connect(
        port: Int,
        method: String = "CONNECT",
    ): Pair<Socket, String> {
        val s = Socket(InetAddress.getLoopbackAddress(), proxyPort)
        s.getOutputStream().write("$method 127.0.0.1:$port HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
        return s to s.readStatusLine()
    }

    @Test
    fun `tunnels bytes intact at the limit`() {
        limiter.bytesPerSecond = 500_000
        val data = Random(7).nextBytes(1_000_000)
        val (s, status) = connect(sink.localPort)
        s.use {
            assertTrue(status.startsWith("HTTP/1.1 200"), status)
            val start = System.nanoTime()
            it.getOutputStream().write(data)
            it.shutdownOutput()
            val answer = it.getInputStream().readAllBytes()
            val seconds = (System.nanoTime() - start) / 1e9
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), answer)
            assertTrue(seconds in 1.4..2.6, "1 MB at 500 kB/s took $seconds s")
        }
    }

    @Test
    fun `unlimited when the rate is zero`() {
        val data = Random(8).nextBytes(4_000_000)
        val (s, status) = connect(sink.localPort)
        s.use {
            assertTrue(status.startsWith("HTTP/1.1 200"), status)
            val start = System.nanoTime()
            it.getOutputStream().write(data)
            it.shutdownOutput()
            assertEquals(32, it.getInputStream().readAllBytes().size)
            assertTrue((System.nanoTime() - start) / 1e9 < 2)
        }
    }

    @Test
    fun `plain requests and other ports are refused`() {
        val (get, getStatus) = connect(sink.localPort, method = "GET")
        get.close()
        assertTrue(getStatus.startsWith("HTTP/1.1 405"), getStatus)
        val (other, otherStatus) = connect(22)
        other.close()
        assertTrue(otherStatus.startsWith("HTTP/1.1 405"), otherStatus)
    }

    @Test
    fun `connect lines are parsed strictly`() {
        assertEquals(
            "drive-api.proton.me" to 443,
            ThrottleProxy.parseConnect("CONNECT drive-api.proton.me:443 HTTP/1.1"),
        )
        assertEquals("::1" to 443, ThrottleProxy.parseConnect("CONNECT [::1]:443 HTTP/1.1"))
        assertEquals(null, ThrottleProxy.parseConnect("GET http://x/ HTTP/1.1"))
        assertEquals(null, ThrottleProxy.parseConnect("CONNECT host HTTP/1.1"))
        assertEquals(null, ThrottleProxy.parseConnect("CONNECT host:99999 HTTP/1.1"))
    }
}
