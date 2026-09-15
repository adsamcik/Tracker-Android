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
		}
	}

	private companion object {
		const val DATABASE = "migration-imported-pressure-maintenance-27-28"
	}
}
