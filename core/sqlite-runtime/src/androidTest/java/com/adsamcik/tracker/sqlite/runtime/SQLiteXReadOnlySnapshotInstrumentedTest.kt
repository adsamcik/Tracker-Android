package com.adsamcik.tracker.sqlite.runtime

import android.content.Context
import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native single-pool WAL snapshots for the exact vendored runtime shipped by Tracker. */
@RunWith(AndroidJUnit4::class)
class SQLiteXReadOnlySnapshotInstrumentedTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
    }

    @After fun cleanup() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun readSnapshotStaysStableWhileSamePoolWriterCommits() {
        val helper = createHelper()
        val writer = Executors.newSingleThreadExecutor()
        helper.setWriteAheadLoggingEnabled(true)
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO snapshot_probe(value) VALUES (1)")
            database.query("PRAGMA journal_mode").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("wal", cursor.getString(0))
            }
            database.beginTransactionReadOnly()
            try {
                assertTrue(database.inTransaction())
                assertEquals(1L, count(database))
                writer.submit {
                    database.beginTransactionNonExclusive()
                    try {
                        database.execSQL("INSERT INTO snapshot_probe(value) VALUES (2)")
                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                }.get(5, TimeUnit.SECONDS)
                // The writer completed, but this already-open snapshot retains its original view.
                assertEquals(1L, count(database))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            assertFalse(database.inTransaction())
            assertEquals(2L, count(database))
            database.execSQL("INSERT INTO snapshot_probe(value) VALUES (3)")
            assertEquals(3L, count(database))
        } finally {
            writer.shutdownNow()
            try {
                assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS))
            } finally {
                helper.close()
            }
        }
    }

    @Test fun readOnlyRejectsWritesAndFinishesListenerRollbackAndCommit() {
        val helper = createHelper()
        helper.setWriteAheadLoggingEnabled(true)
        try {
            val database = helper.writableDatabase
            val callbacks = mutableListOf<String>()
            val listener = object : SQLiteTransactionListener {
                override fun onBegin() { callbacks += "begin" }
                override fun onCommit() { callbacks += "commit" }
                override fun onRollback() { callbacks += "rollback" }
            }
            database.beginTransactionWithListenerReadOnly(listener)
            try {
                assertEquals(0L, count(database))
                assertNotNull(runCatching {
                    database.execSQL("INSERT INTO snapshot_probe(value) VALUES (1)")
                }.exceptionOrNull())
            } finally {
                database.endTransaction()
            }
            assertFalse(database.inTransaction())
            assertEquals(listOf("begin", "rollback"), callbacks)
            assertEquals(0L, count(database))
            database.beginTransactionWithListenerReadOnly(listener)
            try {
                assertEquals(0L, count(database))
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            assertEquals(listOf("begin", "rollback", "begin", "commit"), callbacks)
            database.execSQL("INSERT INTO snapshot_probe(value) VALUES (2)")
            assertEquals(1L, count(database))
        } finally {
            helper.close()
        }
    }

    private fun createHelper(): SupportSQLiteOpenHelper = SQLiteXSupportSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(DATABASE_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE snapshot_probe(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    error("Unexpected upgrade from $oldVersion to $newVersion")
            }).build(),
    )

    private fun count(database: SupportSQLiteDatabase): Long =
        database.query("SELECT COUNT(*) FROM snapshot_probe").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val DATABASE_NAME = "sqlite_read_only_snapshot_test.db"
    }
}
