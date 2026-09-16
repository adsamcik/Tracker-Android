package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSettlementMigration27To28Test {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@After
	fun cleanup() {
		context.deleteDatabase(DATABASE)
	}

	@Test
	fun migrationCreatesOnlyFinalRuntimeSettlementTables() {
		helper.createDatabase(DATABASE, 27).close()

		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			listOf(
				"source_capture_admission_barrier",
				"source_run_retirement",
			).forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 0L, cursor.getLong(0))
				}
			}
			database.query(
				"SELECT 1 FROM sqlite_master WHERE type = 'table' " +
					"AND name = 'source_maintenance_authority'",
			).use { cursor -> assertFalse(cursor.moveToFirst()) }
			assertColumns(
				database,
				"source_capture_admission_barrier",
				listOf(
					"through_authorization_revision",
					"last_admission_ordinal",
					"last_source_sequence",
				),
			)
			assertColumns(
				database,
				"source_run_retirement",
				listOf(
					"callback_entry_barrier_sequence",
					"failed_admission_count",
					"provider_flush_outcome",
					"app_drain_complete",
				),
			)
		}
	}

	private fun assertColumns(
		database: androidx.sqlite.db.SupportSQLiteDatabase,
		table: String,
		expected: List<String>,
	) {
		val actual = buildSet {
			database.query("PRAGMA table_info('$table')").use { cursor ->
				val column = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(column))
			}
		}
		assertTrue("$table missing ${expected - actual}", actual.containsAll(expected))
	}

	private companion object {
		const val DATABASE = "migration-27-28-runtime-settlement"
	}
}
