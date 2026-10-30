// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.toPath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Notices when the app's own jar is removed under the running JVM, as a package upgrade does: classes and
 * static files that aren't loaded yet are gone, so the dashboard loses its CSS and scripts. [onGone] runs once;
 * `serve` exits with a failure status there so systemd's `Restart=on-failure` starts the new version.
 */
class InstallWatch(
    private val jar: Path,
    private val every: Duration = 1.minutes,
    private val onGone: () -> Unit,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(InstallWatch::class.java)
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "install-watch").apply { isDaemon = true } }

    fun start() {
        val ms = every.inWholeMilliseconds
        executor.scheduleWithFixedDelay(::check, ms, ms, TimeUnit.MILLISECONDS)
    }

    private fun check() {
        if (Files.exists(jar)) return
        log.warn("{} was removed (upgraded or uninstalled); stopping so the installed version can start", jar)
        executor.shutdown()
        onGone()
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /** Exit status for [onGone]: EX_TEMPFAIL, so systemd restarts the service. */
        const val EXIT_REINSTALLED = 75

        /** The jar this class was loaded from, or null when running from a classes directory (Gradle, IDE). */
        fun installedJar(): Path? =
            runCatching {
                InstallWatch::class.java.protectionDomain.codeSource
                    ?.location
                    ?.toURI()
                    ?.toPath()
            }.getOrNull()
                ?.takeIf { it.isRegularFile() && it.extension == "jar" }
    }
}
