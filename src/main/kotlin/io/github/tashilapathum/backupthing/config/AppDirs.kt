// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.config

import java.nio.file.Path

/** XDG base directories for this app. */
data class AppDirs(
    val configFile: Path,
    val database: Path,
    val defaultStaging: Path,
) {
    val lockFile: Path get() = database.resolveSibling("backupthing.lock")

    fun staging(settings: Settings): Path =
        settings.system.stagingDir
            .takeIf { it.isNotEmpty() }
            ?.let(Path::of) ?: defaultStaging

    companion object {
        private const val APP = "backupthing"

        fun resolve(
            configOverride: Path? = null,
            env: Map<String, String> = System.getenv(),
        ): AppDirs {
            val home = Path.of(System.getProperty("user.home"))

            fun xdg(
                name: String,
                fallback: String,
            ) = env[name]?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(fallback)
            return AppDirs(
                configFile = configOverride ?: xdg("XDG_CONFIG_HOME", ".config").resolve(APP).resolve("config.toml"),
                database = xdg("XDG_DATA_HOME", ".local/share").resolve(APP).resolve("state.db"),
                defaultStaging = xdg("XDG_CACHE_HOME", ".cache").resolve(APP).resolve("staging"),
            )
        }
    }
}
