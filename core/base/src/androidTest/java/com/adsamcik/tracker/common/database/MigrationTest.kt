package com.adsamcik.tracker.common.database

import android.database.SQLException
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getFloatOrNull
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.MIGRATION_10_11
import com.adsamcik.tracker.shared.base.database.MIGRATION_11_12
import com.adsamcik.tracker.shared.base.database.MIGRATION_12_13
import com.adsamcik.tracker.shared.base.database.MIGRATION_13_14
import com.adsamcik.tracker.shared.base.database.MIGRATION_14_15
import com.adsamcik.tracker.shared.base.database.MIGRATION_15_16
import com.adsamcik.tracker.shared.base.database.MIGRATION_16_17
import com.adsamcik.tracker.shared.base.database.MIGRATION_17_18
import com.adsamcik.tracker.shared.base.database.MIGRATION_18_19
import com.adsamcik.tracker.shared.base.database.MIGRATION_19_20
import com.adsamcik.tracker.shared.base.database.MIGRATION_2_3
import com.adsamcik.tracker.shared.base.database.MIGRATION_20_21
import com.adsamcik.tracker.shared.base.database.MIGRATION_21_22
import com.adsamcik.tracker.shared.base.database.MIGRATION_22_23
import com.adsamcik.tracker.shared.base.database.MIGRATION_23_24
import com.adsamcik.tracker.shared.base.database.MIGRATION_24_25
import com.adsamcik.tracker.shared.base.database.MIGRATION_25_26
import com.adsamcik.tracker.shared.base.database.MIGRATION_26_27
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
import org.junit.Assert.fail
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
	fun migrate11To12() {
		val db = helper.createDatabase(TEST_DB, 11)
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
					1000,
					48.125,
					17.875,
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
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12).apply {
			val compositeTimeIndexCursor = query(
				"SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_location_time_lat_lon'"
			)
			with(compositeTimeIndexCursor) {
				assertTrue(moveToFirst())
				assertEquals("idx_location_time_lat_lon", getString(0))
				assertFalse(moveToNext())
			}
			compositeTimeIndexCursor.close()

			val compositeLatLonIndexCursor = query(
				"SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_location_lat_lon'"
			)
			with(compositeLatLonIndexCursor) {
				assertTrue(moveToFirst())
				assertEquals("idx_location_lat_lon", getString(0))
				assertFalse(moveToNext())
			}
			compositeLatLonIndexCursor.close()

			val droppedIndexCursor = query(
				"""
					SELECT COUNT(*) FROM sqlite_master
					WHERE type = 'index' AND name IN (
						'index_location_data_time',
						'index_location_data_lat',
						'index_location_data_lon'
					)
				""".trimIndent()
			)
			with(droppedIndexCursor) {
				assertTrue(moveToFirst())
				assertEquals(0, getInt(0))
				assertFalse(moveToNext())
			}
			droppedIndexCursor.close()

			val dataCursor = query("SELECT time, lat, lon FROM location_data WHERE id = 1")
			with(dataCursor) {
				assertTrue(moveToFirst())
				assertEquals(1000L, getLong(0))
				assertEquals(48.125, getDouble(1), 0.00001)
				assertEquals(17.875, getDouble(2), 0.00001)
				assertFalse(moveToNext())
			}
			dataCursor.close()
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
	fun migrate13To14() {
		val db = helper.createDatabase(TEST_DB, 13)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 14, true, MIGRATION_13_14).apply {
			execSQL(
				"""
					INSERT INTO daily_summary (
						date_epoch_day,
						total_distance_m,
						total_steps,
						total_duration_ms,
						trip_count,
						active_tracking_ms,
						last_updated_ms,
						created_at
					) VALUES (
						20001,
						12345.5,
						4321,
						3600000,
						3,
						1200000,
						1700001000000,
						1700000000000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO live_stats (
						id,
						date_epoch_day,
						session_distance_m,
						session_steps,
						session_duration_ms,
						day_total_distance_m,
						day_total_steps,
						day_total_duration_ms,
						last_updated_ms
					) VALUES (
						1,
						20001,
						2450.5,
						1200,
						900000,
						12345.5,
						4321,
						3600000,
						1700001000000
					)
				""".trimIndent()
			)

			val dailySummaryCursor = query(
				"""
					SELECT total_distance_m, total_steps, trip_count
					FROM daily_summary
					WHERE date_epoch_day = 20001
				""".trimIndent()
			)
			with(dailySummaryCursor) {
				assertTrue(moveToFirst())
				assertEquals(12345.5, getDouble(0), 0.00001)
				assertEquals(4321, getInt(1))
				assertEquals(3, getInt(2))
				assertFalse(moveToNext())
			}
			dailySummaryCursor.close()

			val liveStatsCursor = query(
				"""
					SELECT session_distance_m, day_total_distance_m, day_total_steps
					FROM live_stats
					WHERE id = 1
				""".trimIndent()
			)
			with(liveStatsCursor) {
				assertTrue(moveToFirst())
				assertEquals(2450.5, getDouble(0), 0.00001)
				assertEquals(12345.5, getDouble(1), 0.00001)
				assertEquals(4321, getInt(2))
				assertFalse(moveToNext())
			}
			liveStatsCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate14To15() {
		val db = helper.createDatabase(TEST_DB, 14)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 15, true, MIGRATION_14_15).apply {
			execSQL(
				"""
					INSERT INTO frequent_place (
						id,
						center_lat_e7,
						center_lon_e7,
						radius_m,
						visit_count,
						first_visit_ms,
						last_visit_ms,
						auto_category,
						created_at
					) VALUES (
						1,
						481250000,
						178750000,
						75.0,
						4,
						1700000000000,
						1700000600000,
						'HOME',
						1700000000000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO inferred_trip (
						id,
						segment_id,
						start_time_ms,
						end_time_ms,
						distance_m,
						steps,
						primary_activity,
						transport_mode,
						departure_place_id,
						arrival_place_id,
						source,
						inference_version,
						leg_count,
						created_at
					) VALUES (
						1,
						99,
						1700000000000,
						1700000900000,
						1525.5,
						2100,
						7,
						'WALK',
						1,
						1,
						'TEST',
						'v1',
						1,
						1700000000000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO trip_leg (
						id,
						trip_id,
						sequence_index,
						start_time_ms,
						end_time_ms,
						distance_m,
						transport_mode,
						created_at
					) VALUES (
						1,
						1,
						0,
						1700000000000,
						1700000900000,
						1525.5,
						'WALK',
						1700000000000
					)
				""".trimIndent()
			)

			val tripCursor = query(
				"""
					SELECT transport_mode, departure_place_id, arrival_place_id, leg_count
					FROM inferred_trip
					WHERE id = 1
				""".trimIndent()
			)
			with(tripCursor) {
				assertTrue(moveToFirst())
				assertEquals("WALK", getString(0))
				assertEquals(1, getInt(1))
				assertEquals(1, getInt(2))
				assertEquals(1, getInt(3))
				assertFalse(moveToNext())
			}
			tripCursor.close()

			val tripLegCursor = query(
				"""
					SELECT trip_id, sequence_index, distance_m, transport_mode
					FROM trip_leg
					WHERE id = 1
				""".trimIndent()
			)
			with(tripLegCursor) {
				assertTrue(moveToFirst())
				assertEquals(1, getInt(0))
				assertEquals(0, getInt(1))
				assertEquals(1525.5, getDouble(2), 0.00001)
				assertEquals("WALK", getString(3))
				assertFalse(moveToNext())
			}
			tripLegCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate15To16() {
		val db = helper.createDatabase(TEST_DB, 15)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 16, true, MIGRATION_15_16).apply {
			execSQL(
				"""
					INSERT INTO exploration_cell (
						id,
						cell_token,
						level,
						quality,
						first_discovered_at,
						last_visited_at,
						visit_count,
						season_bitmask,
						center_lat_e7,
						center_lon_e7,
						created_at
					) VALUES (
						1,
						'89c259',
						12,
						3,
						1700000000000,
						1700000500000,
						2,
						5,
						481250000,
						178750000,
						1700000000000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO exploration_streak (
						type,
						current_count,
						best_count,
						last_increment_day,
						updated_at
					) VALUES (
						'DAILY',
						4,
						7,
						20001,
						1700000600000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO achievement_progress (
						id,
						achievement_id,
						current_value,
						target_value,
						tier,
						unlocked_at,
						updated_at
					) VALUES (
						1,
						'walk-100km',
						75,
						100,
						2,
						NULL,
						1700000600000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO personal_record (
						id,
						metric,
						value,
						achieved_at,
						updated_at
					) VALUES (
						1,
						'longest_distance',
						12000.5,
						1700000700000,
						1700000700000
					)
				""".trimIndent()
			)

			val explorationCellCursor = query(
				"SELECT cell_token, visit_count, season_bitmask FROM exploration_cell WHERE id = 1"
			)
			with(explorationCellCursor) {
				assertTrue(moveToFirst())
				assertEquals("89c259", getString(0))
				assertEquals(2, getInt(1))
				assertEquals(5, getInt(2))
				assertFalse(moveToNext())
			}
			explorationCellCursor.close()

			val streakCursor = query(
				"SELECT current_count, best_count, last_increment_day FROM exploration_streak WHERE type = 'DAILY'"
			)
			with(streakCursor) {
				assertTrue(moveToFirst())
				assertEquals(4, getInt(0))
				assertEquals(7, getInt(1))
				assertEquals(20001, getInt(2))
				assertFalse(moveToNext())
			}
			streakCursor.close()

			val achievementCursor = query(
				"SELECT achievement_id, current_value, target_value, tier FROM achievement_progress WHERE id = 1"
			)
			with(achievementCursor) {
				assertTrue(moveToFirst())
				assertEquals("walk-100km", getString(0))
				assertEquals(75, getInt(1))
				assertEquals(100, getInt(2))
				assertEquals(2, getInt(3))
				assertFalse(moveToNext())
			}
			achievementCursor.close()

			val personalRecordCursor = query(
				"SELECT metric, value FROM personal_record WHERE id = 1"
			)
			with(personalRecordCursor) {
				assertTrue(moveToFirst())
				assertEquals("longest_distance", getString(0))
				assertEquals(12000.5, getDouble(1), 0.00001)
				assertFalse(moveToNext())
			}
			personalRecordCursor.close()
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

	@Test
	@Throws(IOException::class)
	fun migrate17To18() {
		val db = helper.createDatabase(TEST_DB, 17)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 18, true, MIGRATION_17_18).apply {
			execSQL(
				"""
					INSERT INTO pressure_sample (
						id,
						time_ms,
						elapsed_realtime_nanos,
						pressure_hpa,
						altitude_m,
						bucket_id,
						created_at
					) VALUES (
						1,
						1700000000000,
						1000000,
						1013.25,
						215.4,
						7,
						1700000000000
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO ski_run_segment (
						id,
						session_id,
						run_index,
						segment_type,
						start_time_ms,
						end_time_ms,
						vertical_m,
						distance_m,
						max_speed_mps,
						avg_speed_mps,
						created_at
					) VALUES (
						1,
						42,
						0,
						'DOWNHILL',
						1700000000000,
						1700000300000,
						450.5,
						1800.0,
						22.2,
						12.3,
						1700000000000
					)
				""".trimIndent()
			)

			val pressureCursor = query(
				"SELECT pressure_hpa, altitude_m, bucket_id FROM pressure_sample WHERE id = 1"
			)
			with(pressureCursor) {
				assertTrue(moveToFirst())
				assertEquals(1013.25, getDouble(0), 0.00001)
				assertEquals(215.4, getDouble(1), 0.00001)
				assertEquals(7, getInt(2))
				assertFalse(moveToNext())
			}
			pressureCursor.close()

			val skiRunCursor = query(
				"""
					SELECT session_id, segment_type, vertical_m
					FROM ski_run_segment
					WHERE id = 1
				""".trimIndent()
			)
			with(skiRunCursor) {
				assertTrue(moveToFirst())
				assertEquals(42, getInt(0))
				assertEquals("DOWNHILL", getString(1))
				assertEquals(450.5, getDouble(2), 0.00001)
				assertFalse(moveToNext())
			}
			skiRunCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate18To19() {
		val db = helper.createDatabase(TEST_DB, 18)
		db.execSQL(
			"""
				INSERT INTO location_sample (
					id,
					time_ms,
					elapsed_realtime_nanos,
					lat_e7,
					lon_e7,
					alt_m,
					h_acc_m,
					v_acc_m,
					speed_mps,
					speed_accuracy_mps,
					provider,
					quality,
					motion_state,
					policy,
					bucket_id,
					created_at
				) VALUES (
					1,
					1700000000000,
					1000000,
					481250000,
					178750000,
					123.4,
					5.0,
					1.2,
					3.4,
					0.5,
					'gps',
					'HIGH',
					'WALKING',
					'STANDARD',
					11,
					1700000000000
				)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 19, true, MIGRATION_18_19).apply {
			val columnCursor = query(
				"""
					SELECT name, type, "notnull"
					FROM pragma_table_info('location_sample')
					WHERE name = 'raw_gps_alt_m'
				""".trimIndent()
			)
			with(columnCursor) {
				assertTrue(moveToFirst())
				assertEquals("raw_gps_alt_m", getString(0))
				assertEquals("REAL", getString(1))
				assertEquals(0, getInt(2))
				assertFalse(moveToNext())
			}
			columnCursor.close()

			val dataCursor = query("SELECT alt_m, raw_gps_alt_m FROM location_sample WHERE id = 1")
			with(dataCursor) {
				assertTrue(moveToFirst())
				assertEquals(123.4, getDouble(0), 0.00001)
				assertEquals(null, getDoubleOrNull(1))
				assertFalse(moveToNext())
			}
			dataCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate19To20_addsDistanceAnomalyFlagToSessionSegment() {
		val db = helper.createDatabase(TEST_DB, 19)
		db.execSQL(
			"""
				INSERT INTO session_segment (
					id, start_time_ms, end_time_ms, distance_m, steps,
					primary_activity, activity_confidence, sample_count,
					source, inference_version, created_at
				) VALUES (
					1, 1000, 2000, 9393800.0, 12,
					NULL, NULL, 10,
					'USER_CREATED', 'legacy_tracker_bridge', 3000
				)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 20, true, MIGRATION_19_20).apply {
			val cursor = query("SELECT has_distance_anomaly FROM session_segment WHERE id = 1")
			with(cursor) {
				assertTrue(moveToFirst())
				assertEquals(0, getInt(0))
				assertFalse(moveToNext())
			}
			cursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate20To21_backfillsPopulatedLegacyTablesAndDropsThem() {
		createVersion20Database {
			execSQL(
				"""
					INSERT INTO cell_location (
						id,
						time,
						mcc,
						mnc,
						cell_id,
						type,
						asu,
						lat,
						lon,
						alt
					) VALUES (
						1,
						1700000000000,
						'230',
						'01',
						987654,
						13,
						42,
						48.125,
						17.875,
						250.0
					)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO wifi_data (
						bssid,
						longitude,
						latitude,
						altitude,
						first_seen,
						last_seen,
						ssid,
						capabilities,
						frequency,
						level
					) VALUES (
						'00:11:22:33:44:55',
						18.5,
						49.25,
						150.0,
						1699999900000,
						1700000050000,
						'Tracker WiFi',
						'[WPA2-PSK-CCMP][ESS]',
						2412,
						-55
					)
				""".trimIndent()
			)
		}

		migrate20To21Database().apply {
			query(
				"""
					SELECT cell_id, mcc, mnc, lat_e7, lon_e7, provenance
					FROM cell_sample
					WHERE time_ms = 1700000000000
				""".trimIndent()
			).use { cellSampleCursor ->
				assertTrue(cellSampleCursor.moveToFirst())
				assertEquals(987654, cellSampleCursor.getInt(0))
				assertEquals(230, cellSampleCursor.getInt(1))
				assertEquals(1, cellSampleCursor.getInt(2))
				assertEquals(481250000, cellSampleCursor.getInt(3))
				assertEquals(178750000, cellSampleCursor.getInt(4))
				assertEquals("LEGACY_MIGRATION", cellSampleCursor.getString(5))
				assertFalse(cellSampleCursor.moveToNext())
			}

			query(
				"""
					SELECT bssid, time_ms, ssid, lat_e7, lon_e7, provenance
					FROM wifi_observation
					WHERE bssid = '00:11:22:33:44:55'
				""".trimIndent()
			).use { wifiObservationCursor ->
				assertTrue(wifiObservationCursor.moveToFirst())
				assertEquals("00:11:22:33:44:55", wifiObservationCursor.getString(0))
				assertEquals(1700000050000L, wifiObservationCursor.getLong(1))
				assertEquals("Tracker WiFi", wifiObservationCursor.getString(2))
				assertEquals(492500000, wifiObservationCursor.getInt(3))
				assertEquals(185000000, wifiObservationCursor.getInt(4))
				assertEquals("LEGACY_MIGRATION", wifiObservationCursor.getString(5))
				assertFalse(wifiObservationCursor.moveToNext())
			}

			assertTableIsDropped("tracker_session")
			assertTableIsDropped("location_data")
			assertTableIsDropped("wifi_data")
			assertTableIsDropped("cell_location")
			assertTableIsDropped("location_wifi_count")

			query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'network_operator'").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1, cursor.getInt(0))
				assertFalse(cursor.moveToNext())
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate20To21_handlesEmptyLegacyTables() {
		createVersion20Database()

		migrate20To21Database().apply {
			assertTableRowCount("cell_sample", 0)
			assertTableRowCount("wifi_observation", 0)
			assertTableIsDropped("wifi_data")
			assertTableIsDropped("cell_location")
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate20To21_handlesEdgeCaseLegacyRows() {
		val longSsid = "SSID-" + "X".repeat(240)
		val longCapabilities = "[EDGE]-" + "Y".repeat(240)

		createVersion20Database {
			execSQL(
				"""
					INSERT INTO cell_location (
						id,
						time,
						mcc,
						mnc,
						cell_id,
						type,
						asu,
						lat,
						lon,
						alt
					) VALUES
						(
							1,
							1700000100000,
							'999',
							'999',
							2147483647,
							-2147483648,
							2147483647,
							-89.9999999,
							179.9999999,
							NULL
						),
						(
							2,
							1700000100000,
							'999',
							'999',
							2147483647,
							-2147483648,
							2147483647,
							-89.9999999,
							179.9999999,
							NULL
						)
				""".trimIndent()
			)
			execSQL(
				"""
					INSERT INTO wifi_data (
						bssid,
						longitude,
						latitude,
						altitude,
						first_seen,
						last_seen,
						ssid,
						capabilities,
						frequency,
						level
					) VALUES (
						'AA:BB:CC:DD:EE:FF',
						NULL,
						NULL,
						NULL,
						1,
						9223372036854775806,
						'$longSsid',
						'$longCapabilities',
						2147483647,
						-2147483648
					)
				""".trimIndent()
			)
		}

		migrate20To21Database().apply {
			query(
				"""
					SELECT COUNT(*)
					FROM cell_sample
					WHERE time_ms = 1700000100000 AND cell_id = 2147483647
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2, cursor.getInt(0))
				assertFalse(cursor.moveToNext())
			}

			query(
				"""
					SELECT network_type, signal_strength, lat_e7, lon_e7
					FROM cell_sample
					WHERE time_ms = 1700000100000 AND cell_id = 2147483647
					ORDER BY id
					LIMIT 1
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(-2147483648, cursor.getInt(0))
				assertEquals(2147483647, cursor.getInt(1))
				assertEquals(-899999999, cursor.getInt(2))
				assertEquals(1799999999, cursor.getInt(3))
				assertFalse(cursor.moveToNext())
			}

			query(
				"""
					SELECT time_ms, ssid, capabilities, frequency, level, lat_e7, lon_e7, provenance
					FROM wifi_observation
					WHERE bssid = 'AA:BB:CC:DD:EE:FF'
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(9223372036854775806L, cursor.getLong(0))
				assertEquals(longSsid, cursor.getString(1))
				assertEquals(longCapabilities, cursor.getString(2))
				assertEquals(2147483647, cursor.getInt(3))
				assertEquals(-2147483648, cursor.getInt(4))
				assertTrue(cursor.isNull(5))
				assertTrue(cursor.isNull(6))
				assertEquals("LEGACY_MIGRATION", cursor.getString(7))
				assertFalse(cursor.moveToNext())
			}

			assertTableIsDropped("wifi_data")
			assertTableIsDropped("cell_location")
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate21To22() {
		val db = helper.createDatabase(TEST_DB, 21)
		db.execSQL(
			"""
				INSERT INTO cell_sample (
					id,
					time_ms,
					cell_id,
					lac,
					mcc,
					mnc,
					network_type,
					signal_strength,
					lat_e7,
					lon_e7,
					provenance,
					created_at
				) VALUES (
					1,
					1700000000000,
					5000000000,
					99,
					230,
					1,
					20,
					-85,
					481250000,
					178750000,
					'TEST',
					1700000000000
				)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 22, true, MIGRATION_21_22).apply {
			val columnCursor = query(
				"""
					SELECT name, type, "notnull"
					FROM pragma_table_info('cell_sample')
					WHERE name = 'cell_id'
				""".trimIndent()
			)
			with(columnCursor) {
				assertTrue(moveToFirst())
				assertEquals("cell_id", getString(0))
				assertEquals("INTEGER", getString(1))
				assertEquals(1, getInt(2))
				assertFalse(moveToNext())
			}
			columnCursor.close()

			val dataCursor = query("SELECT cell_id FROM cell_sample WHERE id = 1")
			with(dataCursor) {
				assertTrue(moveToFirst())
				assertEquals(5000000000L, getLong(0))
				assertFalse(moveToNext())
			}
			dataCursor.close()
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate23To26_achievementProgressMatchesEntitySchema() {
		val db = helper.createDatabase(TEST_DB, 23)
		db.execSQL(
			"""
				INSERT INTO achievement_progress (
					id,
					achievement_id,
					current_value,
					target_value,
					tier,
					unlocked_at,
					updated_at
				) VALUES (
					1,
					'legacy-upgrade-seed',
					75,
					100,
					2,
					NULL,
					1700000600000
				)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(
			TEST_DB,
			26,
			true,
			MIGRATION_23_24,
			MIGRATION_24_25,
			MIGRATION_25_26
		).apply {
			val columnInfo = mutableMapOf<String, Pair<Int, String?>>()
			query(
				"""
					SELECT name, "notnull", dflt_value
					FROM pragma_table_info('achievement_progress')
					WHERE name IN ('tier', 'notified_at')
				""".trimIndent()
			).use { cursor ->
				while (cursor.moveToNext()) {
					columnInfo[cursor.getString(0)] = cursor.getInt(1) to if (cursor.isNull(2)) null else cursor.getString(2)
				}
			}

			assertEquals(0, columnInfo.getValue("tier").first)
			assertEquals(0, columnInfo.getValue("notified_at").first)
			assertEquals(null, columnInfo.getValue("notified_at").second)

			query(
				"""
					SELECT achievement_id, current_value, target_value, tier, unlocked_at, updated_at, notified_at
					FROM achievement_progress
					WHERE id = 1
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("legacy-upgrade-seed", cursor.getString(0))
				assertEquals(75, cursor.getInt(1))
				assertEquals(100, cursor.getInt(2))
				assertEquals(2, cursor.getInt(3))
				assertTrue(cursor.isNull(4))
				assertEquals(1700000600000L, cursor.getLong(5))
				assertTrue(cursor.isNull(6))
				assertFalse(cursor.moveToNext())
			}
		}
	}


	@Test
	@Throws(IOException::class)
	fun migrate22To23_addsAllExpectedIndices() {
		helper.createDatabase(TEST_DB, 22).close()

		helper.runMigrationsAndValidate(TEST_DB, 23, true, MIGRATION_22_23).apply {
			val expected = mapOf(
				"route_cache" to "index_route_cache_segment_id",
				"export_log" to "index_export_log_completed_at",
				"frequent_place" to "index_frequent_place_last_visit_ms",
				"exploration_cell" to "index_exploration_cell_level_first_discovered_at",
				"achievement_progress" to "index_achievement_progress_updated_at"
			)
			expected.forEach { (table, indexName) ->
				query("PRAGMA index_list('" + table + "')").use { cursor ->
					val names = buildSet {
						while (cursor.moveToNext()) {
							add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
						}
					}
					assertTrue(
						"Expected index $indexName on $table after MIGRATION_22_23",
						names.contains(indexName)
					)
				}
			}
			// The second achievement_progress index is also created.
			query("PRAGMA index_list('achievement_progress')").use { cursor ->
				val names = buildSet {
					while (cursor.moveToNext()) {
						add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
					}
				}
				assertTrue(
					"Expected index index_achievement_progress_unlocked_at on achievement_progress",
					names.contains("index_achievement_progress_unlocked_at")
				)
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate22To23_indicesAreNonUnique() {
		helper.createDatabase(TEST_DB, 22).close()

		helper.runMigrationsAndValidate(TEST_DB, 23, true, MIGRATION_22_23).apply {
			val indicesToCheck = listOf(
				"index_route_cache_segment_id",
				"index_export_log_completed_at",
				"index_frequent_place_last_visit_ms",
				"index_exploration_cell_level_first_discovered_at",
				"index_achievement_progress_updated_at",
				"index_achievement_progress_unlocked_at"
			)
			indicesToCheck.forEach { name ->
				query("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf<Any?>(name)).use { cursor ->
					assertTrue("Expected $name to exist", cursor.moveToFirst())
					val sql = cursor.getString(0)
					assertFalse("Expected $name to be NOT unique", sql.contains(" UNIQUE ", ignoreCase = true))
				}
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate23To24_preservesExistingAchievementData() {
		val db = helper.createDatabase(TEST_DB, 23)
		db.execSQL(
			"""
				INSERT INTO achievement_progress (
					id, achievement_id, current_value, target_value, tier, unlocked_at, updated_at
				) VALUES (
					1, 'step-1k', 500, 1000, 1, 1700000000000, 1700000600000
				)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO achievement_progress (
					id, achievement_id, current_value, target_value, tier, unlocked_at, updated_at
				) VALUES (
					2, 'distance-10k', 3000, 10000, 2, NULL, 1700000700000
				)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).apply {
			// All existing rows survived the table rebuild.
			query("SELECT COUNT(*) FROM achievement_progress").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2, cursor.getInt(0))
			}

			// notified_at column exists and is null for all existing rows.
			query(
				"SELECT id, achievement_id, current_value, target_value, tier, unlocked_at, notified_at FROM achievement_progress ORDER BY id"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1, cursor.getInt(0))
				assertEquals("step-1k", cursor.getString(1))
				assertEquals(500, cursor.getInt(2))
				assertEquals(1000, cursor.getInt(3))
				assertEquals(1, cursor.getInt(4))
				assertEquals(1700000000000L, cursor.getLong(5))
				assertTrue(cursor.isNull(6))

				assertTrue(cursor.moveToNext())
				assertEquals(2, cursor.getInt(0))
				assertEquals("distance-10k", cursor.getString(1))
				assertTrue(cursor.isNull(5))
				assertTrue(cursor.isNull(6))
				assertFalse(cursor.moveToNext())
			}

			// tier column is now nullable (notnull=0 in pragma_table_info).
			query("""SELECT "notnull" FROM pragma_table_info('achievement_progress') WHERE name = 'tier'""").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0, cursor.getInt(0))
			}

			// Unique and regular indices re-created after the rebuild.
			query("PRAGMA index_list('achievement_progress')").use { cursor ->
				val names = buildSet {
					while (cursor.moveToNext()) {
						add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
					}
				}
				assertTrue(names.contains("index_achievement_progress_achievement_id"))
				assertTrue(names.contains("index_achievement_progress_updated_at"))
				assertTrue(names.contains("index_achievement_progress_unlocked_at"))
			}

			// The unique index on achievement_id is still enforced after rebuild.
			var duplicateInsertFailed = false
			try {
				execSQL(
					"""
						INSERT INTO achievement_progress (
							id, achievement_id, current_value, target_value, tier, unlocked_at, updated_at, notified_at
						) VALUES (
							3, 'step-1k', 999, 1000, NULL, NULL, 1700001000000, NULL
						)
					""".trimIndent()
				)
			} catch (_: SQLException) {
				duplicateInsertFailed = true
			}
			assertTrue(
				"Unique index on achievement_id must prevent duplicates after rebuild",
				duplicateInsertFailed
			)
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate23To24_allowsNullTierForNewRow() {
		helper.createDatabase(TEST_DB, 23).close()

		helper.runMigrationsAndValidate(TEST_DB, 24, true, MIGRATION_23_24).apply {
			execSQL(
				"""
					INSERT INTO achievement_progress (
						achievement_id, current_value, target_value, tier, unlocked_at, updated_at, notified_at
					) VALUES (
						'post-migration', 10, 100, NULL, NULL, 1700000900000, NULL
					)
				""".trimIndent()
			)
			query("SELECT tier, notified_at FROM achievement_progress WHERE achievement_id = 'post-migration'").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertTrue(cursor.isNull(0))
				assertTrue(cursor.isNull(1))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate24To25_createsPendingSignalTable() {
		helper.createDatabase(TEST_DB, 24).close()

		helper.runMigrationsAndValidate(TEST_DB, 25, true, MIGRATION_24_25).apply {
			// Table exists and is empty.
			query("SELECT COUNT(*) FROM pending_signal").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(0, cursor.getInt(0))
			}

			// Required columns exist with correct affinities and NOT NULL constraints.
			val columnInfo = mutableMapOf<String, Triple<String, Int, String?>>()
			query(
				"""SELECT name, type, "notnull", dflt_value FROM pragma_table_info('pending_signal')"""
			).use { cursor ->
				while (cursor.moveToNext()) {
					columnInfo[cursor.getString(0)] =
						Triple(cursor.getString(1), cursor.getInt(2), if (cursor.isNull(3)) null else cursor.getString(3))
				}
			}
			assertEquals(4, columnInfo.size)
			assertEquals("INTEGER", columnInfo.getValue("id").first)
			assertEquals(1, columnInfo.getValue("id").second)
			assertEquals("INTEGER", columnInfo.getValue("session_id").first)
			assertEquals(1, columnInfo.getValue("session_id").second)
			assertEquals("TEXT", columnInfo.getValue("signal_json").first)
			assertEquals(1, columnInfo.getValue("signal_json").second)
			assertEquals("INTEGER", columnInfo.getValue("created_at").first)
			assertEquals(1, columnInfo.getValue("created_at").second)

			// Composite index exists.
			query("PRAGMA index_list('pending_signal')").use { cursor ->
				val names = buildSet {
					while (cursor.moveToNext()) {
						add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
					}
				}
				assertTrue(names.contains("idx_pending_signal_session_time"))
			}

			// Row round-trip.
			execSQL(
				"""
					INSERT INTO pending_signal (id, session_id, signal_json, created_at)
					VALUES (1, 42, '{"type":"LOCATION","lat_e7":481250000}', 1700000000000)
				""".trimIndent()
			)
			query("SELECT session_id, signal_json, created_at FROM pending_signal WHERE id = 1").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(42L, cursor.getLong(0))
				assertEquals("{\"type\":\"LOCATION\",\"lat_e7\":481250000}", cursor.getString(1))
				assertEquals(1700000000000L, cursor.getLong(2))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate25To26_addsQueryIndices() {
		val db = helper.createDatabase(TEST_DB, 25)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, MIGRATION_25_26).apply {
			fun indexNames(table: String): Set<String> {
				val cursor = query("PRAGMA index_list('" + table + "')")
				return cursor.use { c ->
					buildSet {
						while (c.moveToNext()) {
							add(c.getString(c.getColumnIndexOrThrow("name")))
						}
					}
				}
			}

			assertTrue(indexNames("domain_event").contains("index_domain_event_event_type_processor_id"))
			assertTrue(indexNames("export_log").contains("index_export_log_started_at"))
			assertTrue(indexNames("inferred_trip").contains("index_inferred_trip_segment_id"))
		}
	}

	@Test
	@Throws(IOException::class)
	fun migrate26To27_createsChallengeTables() {
		val db = helper.createDatabase(TEST_DB, 26)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 27, true, MIGRATION_26_27).apply {
			fun tableExists(table: String): Boolean =
				query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf(table))
					.use { cursor -> cursor.moveToFirst() }

			assertTrue(tableExists("challenge"))
			assertTrue(tableExists("challenge_history"))
			assertTrue(tableExists("challenge_personal_record"))
			assertTrue(tableExists("xp_ledger"))
			assertTrue(tableExists("player_profile"))
			assertTrue(tableExists("minigame_score"))
		}
	}

	companion object {
		private const val TEST_DB = "migration-test"
	}

	private fun createVersion20Database(setup: SupportSQLiteDatabase.() -> Unit = {}) {
		helper.createDatabase(TEST_DB, 20).apply {
			setup()
			close()
		}
	}

	private fun migrate20To21Database(): SupportSQLiteDatabase =
		helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21)

	private fun SupportSQLiteDatabase.assertTableRowCount(tableName: String, expectedCount: Int) {
		query("SELECT COUNT(*) FROM $tableName").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(expectedCount, cursor.getInt(0))
			assertFalse(cursor.moveToNext())
		}
	}

	private fun SupportSQLiteDatabase.assertTableIsDropped(tableName: String) {
		try {
			query("SELECT 1 FROM $tableName LIMIT 1").close()
			fail("Expected table $tableName to be dropped")
		} catch (_: SQLException) {
		}
	}
}
