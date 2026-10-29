// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InvalidSettingsException(
    val errors: List<ValidationError>,
) : IllegalArgumentException(errors.joinToString("; ") { "${it.field}: ${it.message}" })

/**
 * Loads and saves the TOML config. Consumers collect [settings] so changes apply without a restart.
 */
class SettingsStore(
    private val file: Path,
) {
    private var loadedModified: Long = modified()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** False until a config file has been written; the web UI shows the first-run wizard then. */
    val isConfigured: Boolean get() = Files.exists(file)

    private fun load(): Settings = if (Files.exists(file)) decode(file.readText()) else Settings()

    fun reload() {
        _settings.value = load()
    }

    private fun modified(): Long = if (Files.exists(file)) Files.getLastModifiedTime(file).toMillis() else 0

    /**
     * Picks up hand edits of the file. Returns true when new settings were applied; an unreadable file
     * keeps the current settings and throws so the caller can report it.
     */
    @Synchronized
    fun reloadIfChanged(): Boolean {
        val now = modified()
        if (now == loadedModified) return false
        loadedModified = now
        val updated = load()
        if (updated == _settings.value) return false
        _settings.value = updated
        return true
    }

    /** Validates, writes atomically, then publishes. Throws [InvalidSettingsException] without touching the file. */
    @Synchronized
    fun save(settings: Settings) {
        val errors = SettingsValidator.validate(settings)
        if (errors.isNotEmpty()) throw InvalidSettingsException(errors)
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".config", ".toml.tmp")
        try {
            tmp.writeText(encode(settings))
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        loadedModified = modified()
        _settings.value = settings
    }

    companion object {
        private val toml =
            Toml(
                inputConfig = TomlInputConfig(ignoreUnknownNames = true),
                outputConfig = TomlOutputConfig(),
            )

        fun encode(settings: Settings): String = toml.encodeToString(Settings.serializer(), settings)

        fun decode(text: String): Settings = toml.decodeFromString(Settings.serializer(), text)
    }
}
