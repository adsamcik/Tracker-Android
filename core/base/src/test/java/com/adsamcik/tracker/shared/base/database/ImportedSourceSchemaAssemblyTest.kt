package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedSourceSchemaAssemblyTest {
	private lateinit var room: AppDatabase
	private lateinit var migrationSchema: SupportSQLiteOpenHelper

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<Application>()
		room = AppDatabase.testDatabase(context)
		migrationSchema = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(null)
				.callback(object : SupportSQLiteOpenHelper.Callback(1) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						db.execSQL("CREATE TABLE retained_fixture (id INTEGER NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
						db.execSQL("INSERT INTO retained_fixture VALUES (1, 'retained')")
						createImportedPressureMaintenanceTables(db)
						createImportedAmbientStepsTables(db)
					}

					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
						error("This fixture does not upgrade its isolated helper database")
					}
				})
				.build(),
		)
	}

	@After
	fun tearDown() {
		migrationSchema.close()
		room.close()
	}

	@Test
	fun `additive imported DDL matches Room columns foreign keys and named indexes`() {
		val migrated = migrationSchema.writableDatabase
		val fresh = room.openHelper.writableDatabase

		TABLES.forEach { table ->
			TableInfo.read(migrated, table) shouldBe TableInfo.read(fresh, table)
		}
	}

	@Test
	fun `source table creation can repeat without changing retained data`() {
		val migrated = migrationSchema.writableDatabase
		createImportedPressureMaintenanceTables(migrated)
		createImportedAmbientStepsTables(migrated)

		migrated.query("SELECT value FROM retained_fixture WHERE id = 1").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "retained"
			cursor.moveToNext() shouldBe false
		}
	}

	private companion object {
		val TABLES = listOf(
			"imported_pressure_retention_receipt",
			"imported_pressure_retained_identity",
			"imported_pressure_source_erase",
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
