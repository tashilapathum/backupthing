// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    private val dir: Path = createTempDirectory("bt-settings")

    private fun valid() =
        Settings(
            sources = SourceSettings(roots = listOf(dir.toString())),
            compression =
                CompressionSettings(
                    motionPhotoMode = MotionPhotoMode.AS_IS,
                    videoBackend = VideoBackend.SVT_AV1,
                ),
            schedule = ScheduleSettings(mode = ScheduleMode.INTERVAL, intervalHours = 4),
        )

    @Test
    fun `toml round trip preserves every field`() {
        val s = valid()
        assertEquals(s, SettingsStore.decode(SettingsStore.encode(s)))
    }

    @Test
    fun `defaults are filled in for missing keys and unknown keys are ignored`() {
        val s = SettingsStore.decode("[web]\nport = 9000\nfuture = true\n")
        assertEquals(9000, s.web.port)
        assertEquals(Settings().compression, s.compression)
    }

    @Test
    fun `enums use kebab-case names in the file`() {
        val text = SettingsStore.encode(valid())
        assertTrue("\"svt-av1\"" in text, text)
        assertTrue("\"as-is\"" in text, text)
    }

    @Test
    fun `save writes atomically and publishes`() {
        val file = dir.resolve("sub/config.toml")
        val store = SettingsStore(file)
        assertFalse(store.isConfigured)
        store.save(valid())
        assertTrue(store.isConfigured)
        assertEquals(valid(), store.settings.value)
        assertEquals(valid(), SettingsStore(file).settings.value)
        assertEquals(
            listOf("config.toml"),
            Files.list(file.parent).use { s ->
                s.map { it.fileName.toString() }.toList()
            },
        )
    }

    @Test
    fun `hand edits are picked up, broken files keep the old settings`() {
        val file = dir.resolve("edit.toml")
        val store = SettingsStore(file)
        store.save(valid())
        assertFalse(store.reloadIfChanged())
        file.writeText(file.readText().replace("intervalHours = 4", "intervalHours = 9"))
        Files.setLastModifiedTime(
            file,
            java.nio.file.attribute.FileTime
                .fromMillis(System.currentTimeMillis() + 2000),
        )
        assertTrue(store.reloadIfChanged())
        assertEquals(9, store.settings.value.schedule.intervalHours)
        file.writeText("[schedule\nbroken")
        Files.setLastModifiedTime(
            file,
            java.nio.file.attribute.FileTime
                .fromMillis(System.currentTimeMillis() + 4000),
        )
        assertFailsWith<Exception> { store.reloadIfChanged() }
        assertEquals(9, store.settings.value.schedule.intervalHours)
    }

    @Test
    fun `invalid settings are rejected without touching the file`() {
        val file = dir.resolve("config.toml")
        file.writeText(SettingsStore.encode(valid()))
        val before = file.readText()
        val store = SettingsStore(file)
        val e = assertFailsWith<InvalidSettingsException> { store.save(valid().copy(web = WebSettings(port = 80))) }
        assertEquals(listOf("web.port"), e.errors.map { it.field })
        assertEquals(before, file.readText())
    }

    @Test
    fun `validator catches bad values`() {
        val bad =
            Settings(
                sources =
                    SourceSettings(
                        roots = listOf("relative/path", "/does/not/exist"),
                        photoExtensions = listOf(".jpg", "mp4"),
                    ),
                destination = DestinationSettings(remoteRoot = "my-files/"),
                schedule = ScheduleSettings(dailyTime = "25:99"),
                web = WebSettings(bindAddress = "0.0.0.0"),
                compression = CompressionSettings(nvencCq = 99),
            )
        val fields = SettingsValidator.validate(bad).map { it.field }.toSet()
        val expected =
            setOf(
                "sources.roots[0]",
                "sources.roots[1]",
                "sources.extensions",
                "destination.remoteRoot",
                "schedule.dailyTime",
                "web.accessToken",
                "compression.nvencCq",
            )
        assertEquals(expected, fields)
    }

    @Test
    fun `remote root is only checked in drive mode`() {
        val photos = valid().copy(destination = DestinationSettings(mode = DestinationMode.PHOTOS, remoteRoot = ""))
        assertEquals(emptyList(), SettingsValidator.validate(photos))
        assertEquals(
            listOf(
                "destination.remoteRoot",
            ),
            SettingsValidator
                .validate(
                    photos.copy(destination = photos.destination.copy(mode = DestinationMode.DRIVE)),
                ).map {
                    it.field
                },
        )
        assertTrue("mode = \"photos\"" in SettingsStore.encode(photos))
    }

    @Test
    fun `defaults with a source folder are valid`() {
        assertEquals(emptyList(), SettingsValidator.validate(valid()))
    }

    @Test
    fun `xdg dirs honour env and override`() {
        val d =
            AppDirs.resolve(
                env = mapOf("XDG_CONFIG_HOME" to "/c", "XDG_DATA_HOME" to "/d", "XDG_CACHE_HOME" to "/k"),
            )
        assertEquals(Path.of("/c/backupthing/config.toml"), d.configFile)
        assertEquals(Path.of("/d/backupthing/state.db"), d.database)
        assertEquals(Path.of("/k/backupthing/staging"), d.staging(Settings()))
        assertEquals(Path.of("/x"), d.staging(Settings(system = SystemSettings(stagingDir = "/x"))))
        assertEquals(Path.of("/o.toml"), AppDirs.resolve(Path.of("/o.toml"), emptyMap()).configFile)
    }

    @Test
    fun `dashboard url uses the bind address, or localhost when listening everywhere`() {
        assertEquals("http://127.0.0.1:8787", WebSettings().dashboardUrl)
        assertEquals("http://localhost:9000", WebSettings(bindAddress = "0.0.0.0", port = 9000).dashboardUrl)
        assertEquals("http://localhost:8787", WebSettings(bindAddress = "::").dashboardUrl)
        assertEquals("http://[::1]:8787", WebSettings(bindAddress = "::1").dashboardUrl)
        assertEquals("http://192.168.1.5:8787", WebSettings(bindAddress = "192.168.1.5").dashboardUrl)
    }

    @Test
    fun `bandwidth limits round trip and are checked`() {
        val s =
            valid().copy(
                bandwidth =
                    BandwidthSettings(
                        uploadLimitKBps = 2000,
                        dailyCapMB = 5000,
                        monthlyCapMB = 100_000,
                        monthResetDay = 15,
                    ),
            )
        assertEquals(s, SettingsStore.decode(SettingsStore.encode(s)))
        assertEquals(
            BandwidthSettings(),
            SettingsStore.decode("[web]\nport = 9000\n").bandwidth,
            "older files have no limits",
        )

        fun errors(
            b: BandwidthSettings,
            uploads: Int = 1,
        ) = SettingsValidator
            .validate(valid().copy(concurrency = valid().concurrency.copy(uploads = uploads), bandwidth = b))
            .map { it.field to it.message }
        assertEquals(emptyList(), errors(BandwidthSettings()))
        assertEquals(
            listOf(
                "bandwidth.uploadLimitKBps" to
                    "Use at least 120 kB/s for 4 parallel uploads, or leave empty for no limit",
                "bandwidth.dailyCapMB" to "The daily cap is larger than the monthly cap",
                "bandwidth.monthResetDay" to "Use a day from 1 to 28",
            ),
            errors(
                BandwidthSettings(uploadLimitKBps = 100, dailyCapMB = 10_000, monthlyCapMB = 5_000, monthResetDay = 31),
                uploads = 4,
            ),
        )
        assertEquals(emptyList(), errors(BandwidthSettings(uploadLimitKBps = 30)), "30 kB/s is fine for one upload")
    }
}
