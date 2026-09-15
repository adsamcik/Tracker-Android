package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
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
		}

		openDatabase().let { database ->
			try {
				runBlocking {
					database.importedCellDao().insertDeletionGeneration(
						ImportedCellDeletionGenerationEntity.create(
							RUN, ENTRY, SCOPE, 7L, 1L, 40L,
						),
					)
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
				}
			} finally {
				database.close()
			}
		}
	}

	private fun openDatabase(): AppDatabase = Room.databaseBuilder(
		context,
		AppDatabase::class.java,
		DATABASE,
	).openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory())
		.addMigrations(MIGRATION_27_28)
		.allowMainThreadQueries()
		.build()

	private companion object {
		const val DATABASE = "migration-imported-cell-27-28"
		val ENTRY = "1".repeat(64)
		val RUN = "2".repeat(64)
		val SCOPE = "3".repeat(64)
		val TABLES = listOf(
			"imported_cell_entry_revision",
			"imported_cell_receipt",
			"imported_cell_run",
			"imported_cell_observation",
			"imported_cell_entry_deletion",
			"imported_cell_deletion_generation",
		)
	}
}
