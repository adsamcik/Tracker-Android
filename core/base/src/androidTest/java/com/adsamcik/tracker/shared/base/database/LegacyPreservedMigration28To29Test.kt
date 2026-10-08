package com.adsamcik.tracker.shared.base.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyPreservedMigration28To29Test {
	@get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

	@After fun clearFixture() {
		InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(DATABASE)
	}
	@Before fun prepareFixture() { clearFixture() }

	@Test fun migrationKeepsCanonicalValuesAndLeavesUnavailableOriginalsNullAcrossRoomReopen() {
		helper.createDatabase(DATABASE, 28).use { database ->
			database.execSQL("INSERT INTO location_sample(id, time_ms, elapsed_realtime_nanos, lat_e7, lon_e7, provider, quality, created_at) VALUES (101, 1000, 0, 501234567, 142345678, 'gps', 'HIGH', 1000)")
		}
		helper.runMigrationsAndValidate(DATABASE, 29, true, MIGRATION_28_29).use { database ->
			database.query("SELECT id, lat_e7, lon_e7, legacy_lat, legacy_lon, legacy_alt_m FROM location_sample").use { row ->
				assertTrue(row.moveToFirst())
				assertEquals(101L, row.getLong(0))
				assertEquals(501234567, row.getInt(1))
				assertEquals(142345678, row.getInt(2))
				for (column in 3..5) assertTrue(row.isNull(column))
			}
			listOf("legacy_rejected_tracker_session", "legacy_location_wifi_count", "network_operator").forEach { table ->
				database.query("SELECT COUNT(*) FROM `$table`").use { row ->
					assertTrue(row.moveToFirst())
					assertEquals(0L, row.getLong(0))
				}
			}
		}
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val database = AppDatabase.fileBuilder(context, DATABASE).allowMainThreadQueries().build()
		try {
			database.openHelper.writableDatabase.query("SELECT lat_e7, legacy_lat FROM location_sample WHERE id = 101").use { row ->
				assertTrue(row.moveToFirst())
				assertEquals(501234567, row.getInt(0))
				assertTrue(row.isNull(1))
			}
		} finally { database.close() }
	}

	private companion object { const val DATABASE = "legacy-preserved-28-29-test.db" }
}
