// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.util

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

object Logging {
    /** Applies the configured level to the app's loggers (live, no restart). */
    fun setLevel(level: String) {
        (LoggerFactory.getLogger("io.github.tashilapathum.backupthing") as Logger).level =
            Level.toLevel(level, Level.INFO)
    }
}
