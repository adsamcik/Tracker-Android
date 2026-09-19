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
						createImportedPortableStepsCountDomainTables(db)
						createAdditionalTrackingTables(db)
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
	fun `additive tracking DDL matches Room columns foreign keys and named indexes`() {
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
		createImportedPortableStepsCountDomainTables(migrated)
		createAdditionalTrackingTables(migrated)

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
			"imported_pressure_identity_fence",
			"imported_pressure_source_erase",
			"imported_pressure_source_erase_witness",
			"imported_ambient_steps_archive",
			"imported_ambient_steps_receipt",
			"imported_ambient_steps_archive_day",
			"imported_ambient_steps_day_revision",
			"imported_ambient_steps_fact",
			"imported_ambient_steps_gap",
			"imported_ambient_steps_day_fence",
			"imported_ambient_steps_protected_identity",
			"imported_ambient_steps_source_fence",
			"imported_steps_count_domain_graph",
			"imported_steps_count_domain_receipt",
			"imported_steps_count_domain_owner_revision",
			"imported_steps_count_domain_completeness",
			"imported_steps_count_domain_root",
			"imported_steps_count_domain_binding",
			"imported_steps_file_receipt",
			"imported_steps_count_domain_owner_fence",
			"wifi_selected_deletion_receipt",
			"wifi_selected_deletion_protected_identity",
			"imported_cell_entry_deletion_receipt",
			"imported_cell_deleted_identity",
			"cell_captured_entry_deletion_receipt",
			"cell_captured_deleted_run",
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
			"source_capture_admission_barrier",
			"source_run_retirement",
		)
	}
}
