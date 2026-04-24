package com.adsamcik.tracker.common.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.DebugDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Migration coverage for DebugDatabase.
 *
 * The released (main) build ships DebugDatabase at v1. Dev/v10 introduces
 * `MIGRATION_1_2` which adds a `time` index on `debug_activity`. The test
 * asserts both the schema identity at v2 (via runMigrationsAndValidate) and
 * the new index's presence and behavior.
 */
@RunWith(AndroidJUnit4::class)
class DebugDatabaseMigrationTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		DebugDatabase::class.java.canonicalName,
		FrameworkSQLiteOpenHelperFactory()
	)

	@Test
	@Throws(IOException::class)
	fun migrate1To2_preservesExistingRowsAndAddsTimeIndex() {
		val db = helper.createDatabase(TEST_DB, 1)
		db.execSQL(
			"""
				INSERT INTO debug_activity (id, time, action, activity, confidence)
				VALUES (1, 1700000000000, 'started', 7, 80)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO debug_activity (id, time, action, activity, confidence)
				VALUES (2, 1700000100000, NULL, 3, 40)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 2, true, DebugDatabase.MIGRATION_1_2).apply {
			// Existing rows are preserved verbatim.
			query("SELECT id, time, action, activity, confidence FROM debug_activity ORDER BY id").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1, cursor.getInt(0))
				assertEquals(1700000000000L, cursor.getLong(1))
				assertEquals("started", cursor.getString(2))
				assertEquals(7, cursor.getInt(3))
				assertEquals(80, cursor.getInt(4))

				assertTrue(cursor.moveToNext())
				assertEquals(2, cursor.getInt(0))
				assertEquals(1700000100000L, cursor.getLong(1))
				assertTrue(cursor.isNull(2))
				assertEquals(3, cursor.getInt(3))
				assertEquals(40, cursor.getInt(4))

				assertFalse(cursor.moveToNext())
			}

			// New non-unique index `index_debug_activity_time` is present.
			query("PRAGMA index_list('debug_activity')").use { cursor ->
				val found = buildSet {
					while (cursor.moveToNext()) {
						add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
					}
				}
				assertTrue(
					"Expected index_debug_activity_time after MIGRATION_1_2. Got: $found",
					found.contains("index_debug_activity_time")
				)
			}

			// Confirm the index covers the `time` column.
			query("PRAGMA index_info('index_debug_activity_time')").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("time", cursor.getString(cursor.getColumnIndexOrThrow("name")))
				assertFalse(cursor.moveToNext())
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate1To2_isIdempotentForRepeatedApplication() {
		// MIGRATION_1_2 uses CREATE INDEX IF NOT EXISTS. If re-run on a DB
		// that already has the index, it must not throw. This safeguards
		// against Room re-running migrations during recovery.
		val db = helper.createDatabase(TEST_DB, 1)
		db.execSQL("CREATE INDEX index_debug_activity_time ON debug_activity(time)")
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 2, true, DebugDatabase.MIGRATION_1_2).apply {
			query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_debug_activity_time'")
				.use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(1, cursor.getInt(0))
				}
		}
	}

	companion object {
		private const val TEST_DB = "debug-migration-test"
	}
}
