package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedCellMigration27To28Test {
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
	fun importedCellTablesAreAdditiveAndDurableAcrossProductionReopen() {
		helper.createDatabase(DATABASE, 27).close()
		helper.runMigrationsAndValidate(DATABASE, 28, true, MIGRATION_27_28).use { database ->
			TABLES.forEach { table ->
				database.query("SELECT COUNT(*) FROM $table").use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(table, 0L, cursor.getLong(0))
				}
			}
			INDICES.forEach { index ->
				database.query(
					"SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?",
					arrayOf(index),
				).use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(index, 1L, cursor.getLong(0))
				}
			}
		}

		openDatabase().let { database ->
			try {
				runBlocking {
					database.importedCellDao().insertDeletionGeneration(
						ImportedCellDeletionGenerationEntity.create(
							RUN, ENTRY, SCOPE, 7L, 1L, 40L,
						),
					)
					val deletion = ImportedCellEntryDeletionEntity.create(ENTRY, 7L, 1L, 40L)
					val identities = listOf(
						ImportedCellDeletedIdentityEntity.create(
							ENTRY, ENTRY, ImportedCellDeletedIdentityEntity.ENTRY,
							contentChecksum = CONTENT,
						),
						ImportedCellDeletedIdentityEntity.create(
							RUN, ENTRY, ImportedCellDeletedIdentityEntity.RUN, RUN,
							deletionScopeDigest = SCOPE,
							runStartTimeMs = 10L,
							runEndTimeMs = 20L,
							contentChecksum = CONTENT,
						),
						ImportedCellDeletedIdentityEntity.create(
							SCOPE, ENTRY, ImportedCellDeletedIdentityEntity.DELETION_SCOPE, RUN,
							deletionScopeDigest = SCOPE,
						),
						ImportedCellDeletedIdentityEntity.create(
							OBSERVATION,
							ENTRY,
							ImportedCellDeletedIdentityEntity.OBSERVATION,
							RUN,
							contentChecksum = CONTENT,
							observationOrdinal = 0,
						),
					)
					database.importedCellDao().insertEntryDeletion(deletion)
					database.importedCellDao().insertEntryDeletionReceipt(
						ImportedCellEntryDeletionReceiptEntity.create(
							deletion,
							CONTENT,
							"MANUAL",
							"UNKNOWN",
							10L,
							20L,
							30L,
							null,
							1,
							1,
							1,
							1,
							identities,
						),
					)
					database.importedCellDao().insertDeletedIdentities(identities)
				}
			} finally {
				database.close()
			}
		}
		openDatabase().let { database ->
			try {
				runBlocking {
					assertEquals(
						1L,
						database.importedCellDao()
							.deletionGenerationOwners(listOf(RUN), 2).single().generation,
					)
					assertEquals(
						4,
						database.importedCellDao().deletedIdentitiesForEntry(ENTRY, 5).size,
					)
					assertEquals(
						CONTENT,
						database.importedCellDao().entryDeletionReceipt(ENTRY)
							?.deletedContentChecksum,
					)
				}
			} finally {
				database.close()
			}
		}
	}

	private fun openDatabase(): AppDatabase = AppDatabase.fileBuilder(
		context,
		DATABASE,
	).openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
		.allowMainThreadQueries()
		.build()

	private companion object {
		const val DATABASE = "migration-imported-cell-27-28"
		val ENTRY = "1".repeat(64)
		val RUN = "2".repeat(64)
		val SCOPE = "3".repeat(64)
		val OBSERVATION = "4".repeat(64)
		val CONTENT = "5".repeat(64)
		val TABLES = listOf(
			"imported_cell_entry_revision",
			"imported_cell_receipt",
			"imported_cell_run",
			"imported_cell_observation",
			"imported_cell_entry_deletion",
			"imported_cell_deletion_generation",
			"imported_cell_entry_deletion_receipt",
			"imported_cell_deleted_identity",
			"cell_captured_entry_deletion_receipt",
			"cell_captured_deleted_run",
		)
		val INDICES = listOf(
			"idx_imported_cell_entry_time_range",
			"idx_imported_cell_observation_time_range",
			"idx_imported_cell_deleted_identity_entry",
			"idx_cell_captured_entry_deletion_identity",
			"idx_cell_captured_deleted_run_segment",
			"idx_cell_captured_deleted_run_scope",
		)
	}
}
