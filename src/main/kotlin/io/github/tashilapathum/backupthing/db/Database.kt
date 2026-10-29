// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.db

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

enum class MediaStatus { NEW, ENCODING, ENCODED, UPLOADING, DONE, SKIPPED, FAILED }

object Database {
    /** Opens (creating or migrating) the state database. Pass `null` for an in-memory DB in tests. */
    fun open(file: Path?): Pair<BackupDatabase, JdbcSqliteDriver> {
        val url =
            if (file == null) {
                JdbcSqliteDriver.IN_MEMORY
            } else {
                Files.createDirectories(file.toAbsolutePath().parent)
                "jdbc:sqlite:${file.toAbsolutePath()}"
            }
        val props =
            Properties().apply {
                put("foreign_keys", "true")
                put("busy_timeout", "10000")
            }
        val driver = JdbcSqliteDriver(url, props, BackupDatabase.Schema)
        if (file != null) driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        val db =
            BackupDatabase(
                driver,
                media_fileAdapter =
                    Media_file.Adapter(
                        kindAdapter = EnumColumnAdapter(),
                        statusAdapter = EnumColumnAdapter(),
                    ),
            )
        return db to driver
    }
}
