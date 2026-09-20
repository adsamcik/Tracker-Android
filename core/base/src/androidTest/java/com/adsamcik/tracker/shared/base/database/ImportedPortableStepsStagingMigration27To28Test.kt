package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsFullClearStagingSchema
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedPortableStepsStagingMigration27To28Test {
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
	fun migrationCreatesDiskBackedOperationScopedStagingAndExactTraversalIndex() {
		helper.createDatabase(DATABASE, 27).close()

		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			ImportedPortableStepsFullClearStagingSchema.TABLES_IN_DELETE_ORDER.forEach { table ->
				database.query(
					"SELECT COUNT(*) FROM main.sqlite_master " +
						"WHERE type = 'table' AND name = ?",
					arrayOf(table),
				).use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 1L, cursor.getLong(0))
				}
				assertEquals(
					table,
					"operation_id",
					columns(database, table).first(),
				)
			}
			database.query(
				"SELECT COUNT(*) FROM sqlite_temp_master " +
					"WHERE name LIKE 'imported_steps_full_clear_%'",
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0L, cursor.getLong(0))
			}
			assertIndex(
				database,
				"idx_imported_steps_entry_cursor",
				listOf("start_time_ms" to true, "identity" to true),
				unique = false,
			)
			assertIndex(
				database,
				ImportedPortableStepsFullClearStagingSchema.BINDING_GRAPH_INDEX,
				listOf(
					"operation_id" to false,
					"graph_identity" to false,
					"consumed" to false,
				),
				unique = false,
			)
			assertIndex(
				database,
				ImportedPortableStepsFullClearStagingSchema.EXPLICIT_REVISION_INDEX,
				listOf(
					"operation_id" to false,
					"owner_kind" to false,
					"owner_identity" to false,
					"owner_revision" to false,
				),
				unique = true,
			)

			insertBindingStage(database, "operation-a", 'a')
			insertBindingStage(database, "operation-b", 'a')
			database.beginTransaction()
			try {
				database.execSQL(
					"DELETE FROM main.${ImportedPortableStepsFullClearStagingSchema.BINDING_TABLE} " +
						"WHERE operation_id = ?",
					arrayOf("operation-a"),
				)
				database.setTransactionSuccessful()
			} finally {
				database.endTransaction()
			}
			assertEquals(0L, stagedRows(database, "operation-a"))
			assertEquals(1L, stagedRows(database, "operation-b"))

			database.beginTransaction()
			try {
				insertBindingStage(database, "rolled-back", 'c')
			} finally {
				database.endTransaction()
			}
			assertEquals(0L, stagedRows(database, "rolled-back"))

			database.beginTransaction()
			try {
				ImportedPortableStepsFullClearStagingSchema.TABLES_IN_DELETE_ORDER.forEach { table ->
					database.execSQL("DELETE FROM main.$table")
				}
				database.setTransactionSuccessful()
			} finally {
				database.endTransaction()
			}
			assertEquals(0L, stagedRows(database, "operation-b"))
		}
	}

	private fun insertBindingStage(
		database: SupportSQLiteDatabase,
		operationId: String,
		identitySuffix: Char,
	) {
		database.execSQL(
			"INSERT INTO main.${ImportedPortableStepsFullClearStagingSchema.BINDING_TABLE} (" +
				"operation_id, product_kind, product_identity, product_revision, graph_identity, " +
				"source_schema_version, source_receipt_identity, source_archive_identity, " +
				"source_archive_content_checksum, consumed" +
				") VALUES (?, 'SESSION_ENTRY', ?, 1, ?, 1, NULL, NULL, NULL, 0)",
			arrayOf(
				operationId,
				"sha256:" + identitySuffix.toString().repeat(64),
				"sha256:" + "f".repeat(64),
			),
		)
	}

	private fun stagedRows(database: SupportSQLiteDatabase, operationId: String): Long =
		database.query(
			"SELECT COUNT(*) FROM main.${ImportedPortableStepsFullClearStagingSchema.BINDING_TABLE} " +
				"WHERE operation_id = ?",
			arrayOf(operationId),
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			cursor.getLong(0)
		}

	private fun columns(database: SupportSQLiteDatabase, table: String): List<String> = buildList {
		database.query("PRAGMA table_info('$table')").use { cursor ->
			val name = cursor.getColumnIndexOrThrow("name")
			while (cursor.moveToNext()) add(cursor.getString(name))
		}
	}

	private fun assertIndex(
		database: SupportSQLiteDatabase,
		name: String,
		expected: List<Pair<String, Boolean>>,
		unique: Boolean,
	) {
		database.query(
			"SELECT sql FROM main.sqlite_master WHERE type = 'index' AND name = ?",
			arrayOf(name),
		).use { cursor ->
			assertTrue(name, cursor.moveToFirst())
			assertEquals(
				name,
				unique,
				cursor.getString(0).startsWith("CREATE UNIQUE INDEX", ignoreCase = true),
			)
		}
		val actual = buildList {
			database.query("PRAGMA index_xinfo('$name')").use { cursor ->
				val column = cursor.getColumnIndexOrThrow("name")
				val descending = cursor.getColumnIndexOrThrow("desc")
				val key = cursor.getColumnIndexOrThrow("key")
				while (cursor.moveToNext()) {
					if (cursor.getInt(key) != 0) {
						add(cursor.getString(column) to (cursor.getInt(descending) != 0))
					}
				}
			}
		}
		assertEquals(name, expected, actual)
	}

	private companion object {
		const val DATABASE = "migration-27-28-imported-portable-steps-staging"
	}
}
