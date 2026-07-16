package com.lazyreader.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hand-builds a v1-shaped table rather than using Room's exportSchema/
 * MigrationTestHelper machinery, since exportSchema is off (AppDatabase.kt)
 * and this is the only migration so far — not worth the extra schema-json
 * bookkeeping for one ALTER TABLE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    @Test
    fun `migration 1 to 2 adds type column defaulting to pdf`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE recent_documents (" +
                            "uri TEXT NOT NULL PRIMARY KEY, " +
                            "displayName TEXT NOT NULL, " +
                            "totalPages INTEGER NOT NULL, " +
                            "currentPage INTEGER NOT NULL, " +
                            "lastOpenedAt INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)

        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO recent_documents (uri, displayName, totalPages, currentPage, lastOpenedAt) " +
                "VALUES ('u1', 'Doc', 5, 0, 100)",
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("PRAGMA table_info(recent_documents)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columnNames = generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }.toList()
            assertTrue("type" in columnNames)
        }
        db.query("SELECT type FROM recent_documents WHERE uri = 'u1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(RecentDocument.TYPE_PDF, cursor.getString(0))
        }

        helper.close()
    }
}
