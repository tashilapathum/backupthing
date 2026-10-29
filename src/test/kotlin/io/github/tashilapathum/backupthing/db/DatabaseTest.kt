// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.tashilapathum.backupthing.db

import io.github.tashilapathum.backupthing.classify.MediaKind
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseTest {
    @Test
    fun `a 0_1 database gains the usage table and keeps its rows`() {
        val file = createTempDirectory("bt-db").resolve("state.db")
        Database.open(file).let { (db, driver) ->
            db.mediaQueries.insertNew("/src", "a.jpg", MediaKind.PHOTO, 10, 1, 1)
            driver.close()
        }
        // Back to the version 1 schema, as written by 0.1.x.
        DriverManager.getConnection("jdbc:sqlite:$file").use {
            it.createStatement().execute("DROP TABLE upload_usage")
            it.createStatement().execute("PRAGMA user_version = 1")
        }

        val (db, driver) = Database.open(file)
        driver.use {
            assertEquals(
                1,
                db.mediaQueries
                    .selectByStatus(MediaStatus.NEW)
                    .executeAsList()
                    .size,
            )
            db.mediaQueries.insertUsageDay("2026-10-26")
            db.mediaQueries.addUsage(42, "2026-10-26")
            assertEquals(42L, db.mediaQueries.usageBetween("2026-10-01", "2026-11-01").executeAsOne())
        }
        DriverManager.getConnection("jdbc:sqlite:$file").use {
            assertEquals(2, it.createStatement().executeQuery("PRAGMA user_version").getInt(1))
        }
    }
}
