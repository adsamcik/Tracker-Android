package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedPressureMaintenanceMigration27To28Test {
	@get:Rule
	val helper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java,
		emptyList(),
		FrameworkSQLiteOpenHelperFactory(),
	)

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Before
	fun before() {
		context.deleteDatabase(DATABASE)
	}

	@After
	fun after() {
		context.deleteDatabase(DATABASE)
	}

	@Test
	fun importedPressureMaintenanceAuthorityIsAdditive() {
		helper.createDatabase(DATABASE, 27).close()
		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			listOf(
				"imported_pressure_retention_receipt",
				"imported_pressure_retained_identity",
				"imported_pressure_identity_fence",
				"imported_pressure_source_erase",
				"imported_pressure_source_erase_witness",
			).forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 0L, cursor.getLong(0))
				}
			}
			database.query(
				"PRAGMA foreign_key_list(`imported_pressure_retained_identity`)",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("imported_pressure_retention_receipt", cursor.getString(2))
				assertEquals("CASCADE", cursor.getString(6))
			}
			assertColumns(
				database,
				"imported_pressure_entry_deletion",
				listOf(
					"run_deletion_count",
					"run_deletion_set_checksum",
					"identity_fence_count",
					"identity_fence_set_checksum",
				),
			)
			assertColumns(
				database,
				"imported_pressure_retention_receipt",
				listOf(
					"recency_start_time_ms",
					"recency_end_time_ms",
					"recency_tie_identity",
					"identity_fence_set_checksum",
				),
			)
			assertColumns(
				database,
				"imported_pressure_source_erase",
				listOf(
					"provider_registration_generation",
					"legacy_write_fence_owner",
					"legacy_write_fence_generation",
					"legacy_sample_set_checksum",
					"identity_fence_set_checksum",
				),
			)
			database.query(
				"SELECT sql FROM sqlite_master WHERE type = 'table' " +
					"AND name = 'imported_pressure_source_erase'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				val createSql = cursor.getString(0)
				assertTrue(createSql.contains("LEGACY_PRESSURE_SAMPLE"))
				assertTrue(createSql.contains("CONTAINED_PRESSURE_SESSION_FACTS"))
			}
		}
	}

	private fun assertColumns(
		database: androidx.sqlite.db.SupportSQLiteDatabase,
		table: String,
		expected: List<String>,
	) {
		val actual = buildSet {
			database.query("PRAGMA table_info(`$table`)").use { cursor ->
				val column = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(column))
			}
		}
		assertTrue("$table missing ${expected - actual}", actual.containsAll(expected))
	}

	private companion object {
		const val DATABASE = "migration-imported-pressure-maintenance-27-28"
	}
}
