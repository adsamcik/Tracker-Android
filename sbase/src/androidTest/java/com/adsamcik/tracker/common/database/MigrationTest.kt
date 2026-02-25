package com.adsamcik.tracker.common.database

import androidx.core.database.getDoubleOrNull
import androidx.core.database.getFloatOrNull
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.MIGRATION_10_11
import com.adsamcik.tracker.shared.base.database.MIGRATION_11_12
import com.adsamcik.tracker.shared.base.database.MIGRATION_12_13
import com.adsamcik.tracker.shared.base.database.MIGRATION_16_17
import com.adsamcik.tracker.shared.base.database.MIGRATION_2_3
import com.adsamcik.tracker.shared.base.database.MIGRATION_3_4
import com.adsamcik.tracker.shared.base.database.MIGRATION_4_5
import com.adsamcik.tracker.shared.base.database.MIGRATION_5_6
import com.adsamcik.tracker.shared.base.database.MIGRATION_6_7
import com.adsamcik.tracker.shared.base.database.MIGRATION_7_8
import com.adsamcik.tracker.shared.base.database.MIGRATION_8_9
import com.adsamcik.tracker.shared.base.database.MIGRATION_9_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

	@Test
	@Throws(IOException::class)
	fun migrate10To11() {
		val db = helper.createDatabase(TEST_DB, 10)

		// Insert test data with old activity IDs to verify migration
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle, session_activity_id) VALUES (1, 200, 300, 1, 10, 1000, 50, 0, 0, -23)"  // SKI -> SLOPE_SPORTS
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle, session_activity_id) VALUES (2, 400, 600, 1, 20, 2000, 100, 0, 0, -6)"   // SWIM -> WATER_VEHICLE
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle, session_activity_id) VALUES (3, 600, 800, 1, 30, 3000, 150, 0, 0, -19)"  // HIKING -> WALKING
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, steps, distance_on_foot, distance_in_vehicle, session_activity_id) VALUES (4, 800, 1000, 1, 40, 4000, 200, 0, 0, -21)" // RACE -> LAND_VEHICLE
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11).apply {
			val cursor = query("SELECT id, session_activity_id FROM tracker_session ORDER BY id")

			with(cursor) {
				// Verify SKI(-23) -> SLOPE_SPORTS(-22)
				assertTrue(moveToNext())
				assertEquals(1, getInt(0))
				assertEquals(-22, getInt(1))

				// Verify SWIM(-6) -> WATER_VEHICLE(-26)
				assertTrue(moveToNext())
				assertEquals(2, getInt(0))
				assertEquals(-26, getInt(1))

				// Verify HIKING(-19) -> WALKING(-2)
				assertTrue(moveToNext())
				assertEquals(3, getInt(0))
				assertEquals(-2, getInt(1))

				// Verify RACE(-21) -> LAND_VEHICLE(-34)
				assertTrue(moveToNext())
				assertEquals(4, getInt(0))
				assertEquals(-34, getInt(1))
			}

			cursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate12To13() {
		val db = helper.createDatabase(TEST_DB, 12)

		// Legacy tracker_session row that should migrate into session_segment
		db.execSQL(
			"""
				INSERT INTO tracker_session (
					id,
					start,
					`end`,
					user_initiated,
					collections,
					distance,
					distance_on_foot,
					distance_in_vehicle,
					steps,
					session_activity_id
				) VALUES (
					1,
					1_000,
					2_000,
					1,
					8,
					1_234.5,
					900.0,
					334.5,
					120,
					NULL
				)
			""".trimIndent()
		)

		// Minimal location_data samples to validate location_sample/activity_snapshot migrations
		db.execSQL(
			"""
				INSERT INTO location_data (
					id,
					time,
					lat,
					lon,
					alt,
					hor_acc,
					ver_acc,
					speed,
					s_acc,
					activity,
					confidence
				) VALUES (
					1,
					1_000,
					48.1234,
					17.9876,
					200.0,
					5.0,
					1.0,
					2.5,
					0.5,
					3,
					80
				)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO location_data (
					id,
					time,
					lat,
					lon,
					alt,
					hor_acc,
					ver_acc,
					speed,
					s_acc,
					activity,
					confidence
				) VALUES (
					2,
					2_000,
					48.2234,
					18.0876,
					210.0,
					6.0,
					1.5,
					3.0,
					0.4,
					7,
					60
				)
			""".trimIndent()
		)

		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13).apply {
			val segmentCursor = query(
				"""
					SELECT start_time_ms, end_time_ms, distance_m, sample_count, source, inference_version, created_at
					FROM session_segment
				""".trimIndent()
			)

			with(segmentCursor) {
				assertTrue(moveToFirst())
				assertEquals(1_000L, getLong(0))
				assertEquals(2_000L, getLong(1))
				assertEquals(1_234.5f, getFloat(2), 0.001f)
				assertEquals(8, getInt(3))
				assertEquals("LEGACY_MIGRATION", getString(4))
				assertEquals("v12_migration", getString(5))
				assertTrue(getLong(6) > 0)
				assertFalse(moveToNext())
			}
			segmentCursor.close()

			val activityCursor = query("SELECT COUNT(*) FROM activity_snapshot")
			with(activityCursor) {
				assertTrue(moveToFirst())
				assertEquals(2, getInt(0))
			}
			activityCursor.close()

			val locationCursor = query("SELECT COUNT(*) FROM location_sample")
			with(locationCursor) {
				assertTrue(moveToFirst())
				assertEquals(2, getInt(0))
			}
			locationCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate16To17_domainEventTable() {
		val db = helper.createDatabase(TEST_DB, 16)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 17, true, MIGRATION_16_17).apply {
			execSQL(
				"""
					INSERT INTO domain_event (id, event_type, processor_id, timestamp_ms, payload)
					VALUES (1, 'SESSION_COMPLETED', 'stats-engine', 1700000000000, '{"sessionId":42}')
				""".trimIndent()
			)

			val cursor = query("SELECT id, event_type, processor_id, timestamp_ms, payload FROM domain_event WHERE id = 1")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals(1, getInt(0))
				assertEquals("SESSION_COMPLETED", getString(1))
				assertEquals("stats-engine", getString(2))
				assertEquals(1700000000000L, getLong(3))
				assertEquals("{\"sessionId\":42}", getString(4))
				assertFalse(moveToNext())
			}
			cursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate16To17_domainEventCursorTable() {
		val db = helper.createDatabase(TEST_DB, 16)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 17, true, MIGRATION_16_17).apply {
			execSQL(
				"""
					INSERT INTO domain_event_cursor (consumer_id, last_processed_ms)
					VALUES ('achievement-processor', 1700000000000)
				""".trimIndent()
			)

			val cursor = query("SELECT consumer_id, last_processed_ms FROM domain_event_cursor WHERE consumer_id = 'achievement-processor'")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals("achievement-processor", getString(0))
				assertEquals(1700000000000L, getLong(1))
				assertFalse(moveToNext())
			}
			cursor.close()
		}
	}

	companion object {
		private const val TEST_DB = "migration-test"
	}
}

