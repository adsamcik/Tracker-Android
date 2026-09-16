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

/** Schema-generation validation is intentionally deferred until the implementation cohort freezes. */
@RunWith(AndroidJUnit4::class)
class ImportedWifiMigration27To28Test {
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
	fun migrationCreatesEmptyWiFiImportAuthorityWithExactOwnerIndexes() {
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
				"idx_imported_wifi_observation_semantic_owner",
				listOf(
					"entry_identity", "entry_import_revision", "run_identity", "identity", "semantic_revision",
				),
			)
			assertIndex(
				database,
				"idx_imported_wifi_deletion_scope",
				listOf("deletion_scope_digest"),
			)
			assertIndex(
				database,
				"idx_wifi_selected_deletion_protected_receipt",
				listOf("selection_identity", "receipt_origin"),
			)
			assertIndex(
				database,
				"idx_wifi_selected_deletion_protected_identity",
				listOf("protected_identity"),
			)
			assertIndex(
				database,
				"idx_wifi_selected_deletion_aggregate_owner",
				listOf("aggregate_owner_identity"),
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

	private companion object {
		const val DATABASE = "migration-27-28-imported-wifi"
		val TABLES = listOf(
			"imported_wifi_entry_revision",
			"imported_wifi_receipt",
			"imported_wifi_run",
			"imported_wifi_run_zone",
			"imported_wifi_observation",
			"imported_wifi_entry_deletion",
			"imported_wifi_deletion_generation",
			"wifi_selected_deletion_receipt",
			"wifi_selected_deletion_protected_identity",
		)
	}
}
