// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.bandwidth

import io.github.tashilapathum.backupthing.config.BandwidthSettings
import io.github.tashilapathum.backupthing.config.Settings
import io.github.tashilapathum.backupthing.upload.ProtonCli
import io.github.tashilapathum.backupthing.upload.Uploader
import io.github.tashilapathum.backupthing.upload.Uploaders
import java.nio.file.Path

/** Starts the throttling proxy the first time a speed limit is set and keeps its rate in step with the settings. */
class UploadThrottle(
    private val env: Map<String, String> = System.getenv(),
) : AutoCloseable {
    private val limiter = RateLimiter()
    private var proxy: ThrottleProxy? = null

    /** The CLI already goes through a proxy of the user's; chaining to it is not supported. */
    val blockedByEnvironment: Boolean get() = environmentHasProxy(env)

    /** Applies a changed limit to uploads already running. 0 lets them run at full speed. */
    fun apply(settings: BandwidthSettings) {
        limiter.bytesPerSecond = settings.uploadLimitKBps * 1000L
    }

    /** The proxy uploads should use under [settings], or null for a direct connection. */
    @Synchronized
    fun proxyFor(settings: BandwidthSettings): ThrottleProxy? {
        apply(settings)
        if (settings.uploadLimitKBps <= 0 || blockedByEnvironment) return null
        return proxy ?: ThrottleProxy(limiter).start().also { proxy = it }
    }

    fun uploader(
        settings: Settings,
        cli: Path?,
    ): Uploader =
        Uploaders.create(
            settings.destination,
            cli,
            proxyFor(settings.bandwidth),
            settings.bandwidth.uploadLimitKBps,
            settings.concurrency.uploads,
        )

    @Synchronized
    override fun close() {
        proxy?.close()
        proxy = null
    }

    companion object {
        fun environmentHasProxy(env: Map<String, String> = System.getenv()): Boolean =
            ProtonCli.PROXY_VARS.any { !env[it].isNullOrBlank() }
    }
}
