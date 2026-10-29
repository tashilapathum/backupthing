// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing

import java.util.Properties

object BuildInfo {
    /** Filled in at build time from the Gradle project version. */
    val version: String by lazy {
        Properties()
            .apply { BuildInfo::class.java.getResourceAsStream("/backupthing.properties")?.use { load(it) } }
            .getProperty("version", "dev")
    }
}
