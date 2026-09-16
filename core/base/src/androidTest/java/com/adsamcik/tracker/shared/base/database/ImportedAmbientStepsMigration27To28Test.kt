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

/** Parent assembly supplies the additive v27→v28 DDL and generated schema after source convergence. */
@RunWith(AndroidJUnit4::class)
class ImportedAmbientStepsMigration27To28Test {
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
	fun migrationCreatesEmptyAmbientStepsPortableOriginWithExactOwnerIndexes() {
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
				"idx_imported_ambient_steps_receipt_identity",
				listOf("receipt_identity"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_steps_archive_day_owner",
				listOf("archive_identity", "day_identity"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_steps_day_scope",
				listOf("deletion_scope_identity"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_steps_fact_identity",
				listOf("fact_identity"),
			)
			assertIndex(
				database,
				"idx_imported_ambient_steps_fence_scope",
				listOf("deletion_scope_identity"),
			)
			assertColumns(
				database,
				"imported_ambient_steps_source_fence",
				listOf(
					"id",
					"collected_data_epoch",
					"revoked_consent_epoch",
					"deleted_at_ms",
					"deletion_completed",
					"completed_at_ms",
					"reopened_consent_epoch",
					"reopened_at_ms",
					"effect_checksum",
				),
			)
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

	private fun assertColumns(
		database: androidx.sqlite.db.SupportSQLiteDatabase,
		table: String,
		expectedColumns: List<String>,
	) {
		val actual = buildList {
			database.query("PRAGMA table_info('$table')").use { cursor ->
				val column = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(column))
			}
		}
		assertEquals(table, expectedColumns, actual)
	}

	private companion object {
		const val DATABASE = "migration-27-28-imported-ambient-steps"
		val TABLES = listOf(
			"imported_ambient_steps_archive",
			"imported_ambient_steps_receipt",
			"imported_ambient_steps_archive_day",
			"imported_ambient_steps_day_revision",
			"imported_ambient_steps_fact",
			"imported_ambient_steps_gap",
			"imported_ambient_steps_day_fence",
			"imported_ambient_steps_protected_identity",
			"imported_ambient_steps_source_fence",
		)
	}
}
