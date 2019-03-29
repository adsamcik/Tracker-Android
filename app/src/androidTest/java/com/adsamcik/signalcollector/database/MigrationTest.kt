package com.adsamcik.signalcollector.database

import androidx.core.database.getDoubleOrNull
import androidx.core.database.getFloatOrNull
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
			InstrumentationRegistry.getInstrumentation(),
			AppDatabase::class.java.canonicalName,
			FrameworkSQLiteOpenHelperFactory()
	)

	@Test
	@Throws(IOException::class)
	fun migrate2To3() {
		val db = helper.createDatabase(TEST_DB, 2)
		// db has schema version 2. insert some data using SQL queries.
		// You cannot use DAO classes because they expect the latest schema.

		db.execSQL("CREATE TABLE IF NOT EXISTS `location_data` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `alt` REAL NOT NULL, `hor_acc` REAL NOT NULL, `activity` INTEGER NOT NULL, `confidence` INTEGER NOT NULL)")

		db.execSQL("insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (1, '1552026583', '27.30459', '68.39764', -36.2, 26.6, 3, 38)")
		db.execSQL("insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (2, '1522659716', 52.5094874, 16.7474972, -97.4, 67.7, 3, 69)")
		db.execSQL("insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (3, '1528243933', 19.1780491, -96.1288426, -34.0, 31.3, 3, 6)")
		db.execSQL("insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (4, '1544682291', 50.7036309, 18.995304, 0.0, 0.0, 3, 64)")
		db.execSQL("insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (5, '1529254903', 53.5830905, 9.7537598, 45.7, 73.3, 3, 86)")
		// Prepare for the next version.
		db.close()


		// Re-open the database with version 3 and provide
		// MIGRATION_2_3 as the migration process.
		helper.runMigrationsAndValidate(Companion.TEST_DB, 3, true, MIGRATION_2_3).apply {
			var cursor = query("SELECT * FROM location_data WHERE id == 3")

			with(cursor) {
				val hasNext = moveToNext()
				assertTrue(hasNext)
				assertEquals(3, getInt(0))
				assertEquals(1528243933, getLong(1))
				assertEquals(19.1780491, getDouble(2), 0.00001)
				assertEquals(-96.1288426, getDouble(3), 0.00001)
				assertEquals(-34.0, getDouble(4), 0.00001)
				assertEquals(31.3f, getFloat(5), 0.00001f)
				assertEquals(3, getInt(7))
				assertEquals(6, getInt(8))
			}

			cursor = query("SELECT * FROM location_data WHERE id == 4")

			with(cursor) {
				val hasNext = moveToNext()
				assertTrue(hasNext)
				assertEquals(4, getInt(0))
				assertEquals(null, getDoubleOrNull(4))
				assertEquals(null, getFloatOrNull(5))
			}

		}


		// MigrationTestHelper automatically verifies the schema changes,
		// but you need to validate that the data was migrated properly.
	}

	companion object {
		private const val TEST_DB = "migration-test"
	}
}
