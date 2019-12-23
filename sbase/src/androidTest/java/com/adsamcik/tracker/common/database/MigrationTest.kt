package com.adsamcik.tracker.common.database

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

		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (1, '1552026583', '27.30459', '68.39764', -36.2, 26.6, 3, 38)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (2, '1522659716', 52.5094874, 16.7474972, -97.4, 67.7, 3, 69)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (3, '1528243933', 19.1780491, -96.1288426, -34.0, 31.3, 3, 6)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (4, '1544682291', 50.7036309, 18.995304, 0.0, 0.0, 3, 64)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (5, '1529254903', 53.5830905, 9.7537598, 45.7, 73.3, 3, 86)"
		)
		// Prepare for the next version.
		db.close()


		// Re-open the database with version 3 and provide
		// MIGRATION_2_3 as the migration process.
		helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).apply {
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
	}

	@Test
	@Throws(IOException::class)
	fun migrate3To4() {
		val db = helper.createDatabase(TEST_DB, 3)
		db.close()


		// Re-open the database with version 3 and provide
		// MIGRATION_2_3 as the migration process.
		helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
	}

	@Test
	@Throws(IOException::class)
	fun migrate4To5() {
		val db = helper.createDatabase(TEST_DB, 4)

		db.execSQL(
				"INSERT INTO tracking_session (id, start, `end`, collections, distance, steps) VALUES (1, 200, 300, 10, 1000, 50)"
		)
		db.execSQL(
				"INSERT INTO tracking_session (id, start, `end`, collections, distance, steps) VALUES (2, 400, 600, 20, 2000, 100)"
		)
		db.execSQL(
				"INSERT INTO tracking_session (id, start, `end`, collections, distance, steps) VALUES (3, 600, 400, 20, 2000, 100)"
		)
		db.execSQL(
				"INSERT INTO tracking_session (id, start, `end`, collections, distance, steps) VALUES (4, 400, 600, 0, 2000, 100)"
		)

		db.close()


		helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).apply {
			val cursor = query("SELECT * FROM tracker_session WHERE id == 2")

			with(cursor) {
				val hasNext = moveToNext()
				assertTrue(hasNext)
				assertEquals(2, getInt(0))
				assertEquals(400, getInt(1))
				assertEquals(600, getInt(2))
				assertEquals(20, getInt(3))
				assertEquals(2000, getInt(4))
				assertEquals(0, getInt(5))
				assertEquals(0, getInt(6))
				assertEquals(100, getInt(7))
			}

			val cursorCount = query("SELECT COUNT(*) FROM tracker_session")
			with(cursorCount) {
				assertTrue(moveToNext())
				assertEquals(2, getInt(0))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate5To6() {
		val db = helper.createDatabase(TEST_DB, 5)

		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (1, '1552026583', 27.30459, 68.39764, -36.2, 26.6, 3, 38)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (2, '1522659716', 52.5094874, 16.7474972, -97.4, 67.7, 3, 69)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (3, '1528243933', 19.1780491, -96.1288426, -34.0, 31.3, 3, 6)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (4, '1544682291', 50.7036309, 18.995304, 0.0, 0.0, 3, 64)"
		)
		db.execSQL(
				"insert into location_data (id, time, lat, lon, alt, hor_acc, activity, confidence) values (5, '1529254903', 53.5830905, 9.7537598, 45.7, 73.3, 3, 86)"
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).apply {
			val cursor = query("SELECT * FROM location_data WHERE id == 3")

			with(cursor) {
				val hasNext = moveToNext()
				assertTrue(hasNext)
				assertEquals(3, getInt(0))
				assertEquals(1528243933, getLong(1))
				assertEquals(19.1780491, getDouble(2), 0.00001)
				assertEquals(-96.1288426, getDouble(3), 0.00001)
				assertEquals(-34.0, getDouble(4), 0.00001)
				assertEquals(31.3f, getFloat(5), 0.00001f)
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate6To7() {
		val db = helper.createDatabase(TEST_DB, 6)

		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (1, 200, 300, 10, 1000, 50, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (2, 400, 600, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (3, 600, 400, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (4, 400, 600, 0, 2000, 100, 0, 0)"
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7).apply {
			val cursor = query("SELECT * FROM tracker_session WHERE id == 2")

			with(cursor) {
				val hasNext = moveToNext()
				assertTrue(hasNext)
				assertEquals(2, getInt(0))
				assertEquals(400, getInt(1))
				assertEquals(600, getInt(2))
				assertEquals(0, getInt(3))
				assertEquals(20, getInt(4))
				assertEquals(2000, getInt(5))
				assertEquals(0, getInt(6))
				assertEquals(0, getInt(7))
				assertEquals(100, getInt(8))
			}
		}
	}


	@Test
	@Throws(IOException::class)
	fun migrate7To8() {
		val db = helper.createDatabase(TEST_DB, 7)

		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (1, 200, 300, 1, 10, 1000, 50, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (2, 400, 600, 1, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (3, 600, 400, 0, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (4, 400, 600, 0, 0, 2000, 100, 0, 0)"
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8).apply {
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate8To9() {
		val db = helper.createDatabase(TEST_DB, 8)

		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (1, 200, 300, 1, 10, 1000, 50, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (2, 400, 600, 1, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (3, 600, 400, 0, 20, 2000, 100, 0, 0)"
		)
		db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle) VALUES (4, 400, 600, 0, 0, 2000, 100, 0, 0)"
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9).apply {
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate9To10() {
		val db = helper.createDatabase(TEST_DB, 8)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_8_9, MIGRATION_9_10)
	}

	companion object {
		private const val TEST_DB = "migration-test"
	}
}

