package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Authored migration contract; execution remains deferred to the final convergence batch. */
@RunWith(AndroidJUnit4::class)
class AmbientRadioMigration27To28Test {
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
	fun migrationCreatesEmptyAmbientRadioTablesAndStructuralIndexes() {
		helper.createDatabase(DATABASE, 27).close()

		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			TABLES.forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 0L, cursor.getLong(0))
				}
			}
			assertIndex(
				database,
				"idx_ambient_wifi_fact_day",
				listOf("structural_epoch_day", "stored_zone_id", "observed_wall_time_ms"),
			)
			assertIndex(
				database,
				"idx_ambient_cell_fact_day",
				listOf("structural_epoch_day", "stored_zone_id", "observed_wall_time_ms"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_wifi_day",
				listOf("structural_epoch_day", "stored_zone_id", "observed_time_ms"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_cell_day",
				listOf("structural_epoch_day", "stored_zone_id", "observed_time_ms"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_wifi_fact_revision",
				listOf("fact_id", "semantic_revision"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_cell_fact_revision",
				listOf("fact_id", "semantic_revision"),
			)
			assertIndex(
				database,
				"idx_ambient_wifi_retention_effective",
				listOf(
					"scope",
					"effective_boot_id",
					"effective_elapsed_realtime_nanos",
					"approval_revision",
				),
			)
			assertIndex(
				database,
				"idx_ambient_wifi_authority_effective",
				listOf(
					"effective_boot_id",
					"effective_elapsed_realtime_nanos",
					"authority_revision",
				),
			)
			assertIndex(
				database,
				"idx_ambient_cell_retention_effective",
				listOf(
					"scope",
					"effective_boot_id",
					"effective_elapsed_realtime_nanos",
					"approval_revision",
				),
			)
			assertIndex(
				database,
				"idx_ambient_cell_authority_effective",
				listOf(
					"effective_boot_id",
					"effective_elapsed_realtime_nanos",
					"authority_revision",
				),
			)
			assertColumns(
				database,
				"ambient_wifi_authority",
				listOf(
					"rollout_revision",
					"owner_cas_token",
					"reconciliation_attempt",
					"demand_id",
				),
			)
			assertColumns(
				database,
				"imported_ambient_cell_fact",
				listOf("portable_effect_checksum", "retention_approval_revision"),
			)
		}

		private fun assertColumns(
			database: androidx.sqlite.db.SupportSQLiteDatabase,
			table: String,
			expectedColumns: List<String>,
		) {
			val actual = buildSet {
				database.query("PRAGMA table_info('$table')").use { cursor ->
					val column = cursor.getColumnIndexOrThrow("name")
					while (cursor.moveToNext()) add(cursor.getString(column))
				}
			}
			assertTrue("$table missing ${expectedColumns - actual}", actual.containsAll(expectedColumns))
		}
	}

	private fun assertIndex(
		database: androidx.sqlite.db.SupportSQLiteDatabase,
		name: String,
		expectedColumns: List<String>,
	) {
		val actual = buildList {
			database.query("PRAGMA index_info('$name')").use { cursor ->
				val column = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(column))
			}
		}
		assertEquals(name, expectedColumns, actual)
	}

	private companion object {
		const val DATABASE = "migration-27-28-ambient-radio"
		val TABLES = listOf(
			"ambient_wifi_authority",
			"ambient_wifi_retention_authority",
			"ambient_wifi_fact_revision",
			"ambient_wifi_fact_cursor",
			"ambient_wifi_gap",
			"ambient_wifi_deletion_marker",
			"imported_ambient_wifi_fact",
			"imported_ambient_wifi_gap",
			"imported_ambient_wifi_receipt",
			"imported_ambient_wifi_tombstone",
			"ambient_wifi_replay_footprint",
			"ambient_cell_authority",
			"ambient_cell_retention_authority",
			"ambient_cell_fact_revision",
			"ambient_cell_fact_cursor",
			"ambient_cell_gap",
			"ambient_cell_deletion_marker",
			"imported_ambient_cell_fact",
			"imported_ambient_cell_gap",
			"imported_ambient_cell_receipt",
			"imported_ambient_cell_tombstone",
			"ambient_cell_replay_footprint",
		)
	}
}
