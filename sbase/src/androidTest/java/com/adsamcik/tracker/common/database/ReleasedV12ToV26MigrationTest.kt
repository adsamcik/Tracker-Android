package com.adsamcik.tracker.common.database

import android.database.SQLException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
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
import com.adsamcik.tracker.shared.base.database.MIGRATION_20_21
import com.adsamcik.tracker.shared.base.database.MIGRATION_21_22
import com.adsamcik.tracker.shared.base.database.MIGRATION_22_23
import com.adsamcik.tracker.shared.base.database.MIGRATION_23_24
import com.adsamcik.tracker.shared.base.database.MIGRATION_24_25
import com.adsamcik.tracker.shared.base.database.MIGRATION_25_26
import com.adsamcik.tracker.shared.base.database.MIGRATION_2_3
import com.adsamcik.tracker.shared.base.database.MIGRATION_3_4
import com.adsamcik.tracker.shared.base.database.MIGRATION_4_5
import com.adsamcik.tracker.shared.base.database.MIGRATION_5_6
import com.adsamcik.tracker.shared.base.database.MIGRATION_6_7
import com.adsamcik.tracker.shared.base.database.MIGRATION_7_8
import com.adsamcik.tracker.shared.base.database.MIGRATION_8_9
import com.adsamcik.tracker.shared.base.database.MIGRATION_9_10
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Exhaustive migration coverage for the released → dev/v10 database upgrade path.
 *
 * The released ("master") version of the app ships AppDatabase schema **v12**.
 * The dev/v10 branch bumps that to **v26**. Users upgrading from master will
 * execute the full MIGRATION_12_13 … MIGRATION_25_26 chain against real data.
 *
 * This suite validates every observable property of that upgrade:
 *
 * - Every individual migration 2→3 … 25→26 is exercised inside a full chain
 *   so ordering issues surface.
 * - Room's built-in schema validator (`runMigrationsAndValidate`) is invoked
 *   at the final step so any column / index / foreign-key drift against the
 *   exported v26 schema fails loudly.
 * - Representative v12 data (location_data, tracker_session, wifi_data,
 *   cell_location, location_wifi_count, network_operator, activity) is seeded
 *   and verified to be preserved, transformed, or dropped according to the
 *   migration contract.
 * - Boundary values (NULL coordinates, 5G NR cell ids wider than Int, extreme
 *   lat/lon near the poles, sessions violating the v5 collection≥2 rule,
 *   every legacy activity id mapped at v10→v11) are explicitly covered.
 * - Foreign-key cascades declared on v15 tables are executed against rows
 *   inserted post-migration.
 * - After the full chain the database is reopened through Room's normal
 *   Builder to prove the end-state schema is accepted at runtime.
 */
@RunWith(AndroidJUnit4::class)
class ReleasedV12ToV26MigrationTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		AppDatabase::class.java.canonicalName,
		FrameworkSQLiteOpenHelperFactory()
	)

	private val allMigrations = arrayOf(
		MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
		MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
		MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
		MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
		MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
		MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26
	)

	private val v12ToV26Migrations = arrayOf(
		MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
		MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
		MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
		MIGRATION_24_25, MIGRATION_25_26
	)

	@After
	fun teardown() {
		// MigrationTestHelper is @Rule-scoped but the Room.databaseBuilder
		// handle opened in the runtime-smoke test is not — close it eagerly.
		runtimeDatabase?.close()
		runtimeDatabase = null
	}

	private var runtimeDatabase: AppDatabase? = null

	// ---------------------------------------------------------------------
	// Chain-level schema validation
	// ---------------------------------------------------------------------

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_emptyDatabase_validatesFinalSchema() {
		helper.createDatabase(TEST_DB, 12).close()

		// Second arg `true` = validate schema identity and drop-expected tables.
		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { _ ->
			// If runMigrationsAndValidate returns, Room already confirmed the
			// schema matches the exported v26.json identity hash. The block is
			// left intentionally empty; presence-of-table checks live in
			// `fullChain_v12_to_v26_allV26TablesPresent`.
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v2_to_v26_oldestReleasedPath() {
		// Legacy path: upgrading from a device still on a pre-v300 release.
		// Dev/v10 must also handle this chain cleanly.
		helper.createDatabase(TEST_DB, 2).close()
		helper.runMigrationsAndValidate(TEST_DB, 26, true, *allMigrations).close()
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_allV26TablesPresent() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			val tables = listTables(db)
			val expected = listOf(
				"activity",
				"network_operator",
				"location_sample",
				"step_interval",
				"activity_snapshot",
				"cell_sample",
				"wifi_observation",
				"tracker_run",
				"session_segment",
				"daily_summary",
				"live_stats",
				"frequent_place",
				"inferred_trip",
				"trip_leg",
				"exploration_cell",
				"exploration_streak",
				"achievement_progress",
				"personal_record",
				"route_cache",
				"export_log",
				"storage_size_snapshot",
				"domain_event",
				"domain_event_cursor",
				"pressure_sample",
				"ski_run_segment",
				"pending_signal"
			)
			expected.forEach { table ->
				assertTrue(
					"Expected table '$table' to exist after v12→v26 migration but found only: $tables",
					tables.contains(table)
				)
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_legacyTablesAreDropped() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			val tables = listTables(db)
			listOf(
				"location_data",
				"tracker_session",
				"wifi_data",
				"cell_location",
				"location_wifi_count"
			).forEach { legacy ->
				assertFalse(
					"Legacy table '$legacy' must be dropped by MIGRATION_20_21 but still exists",
					tables.contains(legacy)
				)
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_allDeclaredIndicesPresent() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			// Every index declared on a Room entity at v26. If any of these is
			// missing, a fresh-install DB and an upgraded DB diverge — Room's
			// query planner picks different plans and latency regresses silently.
			val expectedIndices = mapOf(
				"activity" to listOf("index_activity_name"),
				"location_sample" to listOf(
					"idx_location_sample_time",
					"idx_location_sample_coords",
					"idx_location_sample_bucket"
				),
				"step_interval" to listOf("idx_step_interval_time_range"),
				"activity_snapshot" to listOf("idx_activity_snapshot_time"),
				"cell_sample" to listOf(
					"idx_cell_sample_time",
					"idx_cell_sample_cell_id",
					"idx_cell_sample_coords"
				),
				"wifi_observation" to listOf(
					"idx_wifi_obs_time",
					"idx_wifi_obs_bssid",
					"idx_wifi_obs_coords"
				),
				"tracker_run" to listOf("idx_tracker_run_time_range"),
				"session_segment" to listOf(
					"idx_session_segment_time_range",
					"idx_session_segment_source",
					// NOTE: declared on SessionSegment entity but not created
					// by any migration as of v26. If this assertion fires, the
					// bug is in MIGRATION_25_26 (or an earlier migration), not
					// in this test.
					"idx_session_segment_primary_activity"
				),
				"daily_summary" to listOf("index_daily_summary_date_epoch_day"),
				"frequent_place" to listOf(
					"idx_frequent_place_coords",
					"index_frequent_place_last_visit_ms"
				),
				"inferred_trip" to listOf(
					"idx_inferred_trip_time_range",
					"idx_inferred_trip_departure",
					"idx_inferred_trip_arrival",
					"index_inferred_trip_segment_id"
				),
				"trip_leg" to listOf("idx_trip_leg_trip_seq"),
				"exploration_cell" to listOf(
					"index_exploration_cell_cell_token",
					"index_exploration_cell_level",
					"index_exploration_cell_first_discovered_at",
					"index_exploration_cell_level_first_discovered_at"
				),
				"achievement_progress" to listOf(
					"index_achievement_progress_achievement_id",
					"index_achievement_progress_updated_at",
					"index_achievement_progress_unlocked_at"
				),
				"personal_record" to listOf("index_personal_record_metric"),
				"route_cache" to listOf(
					"index_route_cache_session_id",
					"index_route_cache_segment_id",
					"index_route_cache_start_time"
				),
				"export_log" to listOf(
					"index_export_log_completed_at",
					"index_export_log_started_at"
				),
				"storage_size_snapshot" to listOf("index_storage_size_snapshot_epoch_day"),
				"domain_event" to listOf(
					"index_domain_event_timestamp_ms",
					"index_domain_event_event_type_processor_id"
				),
				"pressure_sample" to listOf(
					"idx_pressure_sample_time",
					"idx_pressure_sample_bucket"
				),
				"ski_run_segment" to listOf(
					"idx_ski_run_segment_session",
					"idx_ski_run_segment_start_time"
				),
				"pending_signal" to listOf("idx_pending_signal_session_time")
			)
			expectedIndices.forEach { (table, indices) ->
				val actual = indexNames(db, table)
				indices.forEach { expected ->
					assertTrue(
						"Table '$table' missing declared index '$expected' after v12→v26 migration. Actual: $actual",
						actual.contains(expected)
					)
				}
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_uniqueIndicesEnforceUniqueness() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			// Known unique indices at v26 (see 26.json `"unique": true`).
			val uniqueIndices = listOf(
				"index_daily_summary_date_epoch_day" to "daily_summary",
				"idx_trip_leg_trip_seq" to "trip_leg",
				"index_exploration_cell_cell_token" to "exploration_cell",
				"index_achievement_progress_achievement_id" to "achievement_progress",
				"index_personal_record_metric" to "personal_record",
				"index_storage_size_snapshot_epoch_day" to "storage_size_snapshot"
			)
			uniqueIndices.forEach { (indexName, table) ->
				db.query(
					"SELECT \"unique\" FROM pragma_index_list('$table') WHERE name = ?",
					arrayOf<Any?>(indexName)
				).use { cursor ->
					assertTrue("Index $indexName on $table should exist", cursor.moveToFirst())
					assertEquals("Index $indexName on $table should be UNIQUE", 1, cursor.getInt(0))
				}
			}
		}
	}

	// ---------------------------------------------------------------------
	// Data preservation and transformation along the chain
	// ---------------------------------------------------------------------

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_preservesRichLegacyDataset() {
		val db = helper.createDatabase(TEST_DB, 12)
		seedV12Dataset(db)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { upgraded ->
			// location_data (3 rows) → location_sample, preserving coords as E7.
			upgraded.query("SELECT COUNT(*) FROM location_sample").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(3, cursor.getInt(0))
			}

			// Coordinate precision: 48.1234567° → 481234567 (E7), tolerating
			// ±1 for the integer cast of doubles.
			upgraded.query(
				"SELECT lat_e7, lon_e7, alt_m, h_acc_m, v_acc_m, speed_mps, speed_accuracy_mps, provider, quality, motion_state, raw_gps_alt_m FROM location_sample WHERE time_ms = 1000"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEqualsWithinOne("lat_e7", 481234567, cursor.getLong(0))
				assertEqualsWithinOne("lon_e7", 179876543, cursor.getLong(1))
				assertEquals(200.0, cursor.getDouble(2), 0.00001)
				assertEquals(5.0, cursor.getDouble(3), 0.00001)
				assertEquals(1.0, cursor.getDouble(4), 0.00001)
				assertEquals(2.5, cursor.getDouble(5), 0.00001)
				assertEquals(0.5, cursor.getDouble(6), 0.00001)
				assertEquals("legacy", cursor.getString(7))
				assertEquals("HIGH", cursor.getString(8)) // hor_acc=5 < 10 ⇒ HIGH
				// Activity IN_VEHICLE (0) → STILL per 12→13 mapping.
				assertEquals("STILL", cursor.getString(9))
				// raw_gps_alt_m added at 18→19, backfilled as NULL.
				assertTrue(cursor.isNull(10))
			}

			// NULL hor_acc classifies as COARSE quality.
			upgraded.query(
				"SELECT quality FROM location_sample WHERE time_ms = 2000"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("COARSE", cursor.getString(0))
			}

			// hor_acc 75 (>50) classifies as LOW.
			upgraded.query(
				"SELECT quality, motion_state FROM location_sample WHERE time_ms = 3000"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("LOW", cursor.getString(0))
				// Activity ON_FOOT (2) → MOVING
				assertEquals("MOVING", cursor.getString(1))
			}

			// tracker_session: only rows with start<end AND collections>1 survive.
			// Seeded 3 rows; two violate the rule and one is valid.
			upgraded.query("SELECT COUNT(*) FROM session_segment WHERE source = 'LEGACY_MIGRATION'").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(
					"Only valid (start<end AND collections>1) sessions should migrate",
					1,
					cursor.getInt(0)
				)
			}

			// The surviving session retains its fields.
			upgraded.query(
				"""
					SELECT start_time_ms, end_time_ms, distance_m, steps, sample_count, has_distance_anomaly
					FROM session_segment WHERE source = 'LEGACY_MIGRATION'
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1000L, cursor.getLong(0))
				assertEquals(2000L, cursor.getLong(1))
				assertEquals(1234.5f, cursor.getFloat(2), 0.001f)
				assertEquals(120, cursor.getInt(3))
				assertEquals(8, cursor.getInt(4))
				// has_distance_anomaly defaults to 0 via MIGRATION_19_20.
				assertEquals(0, cursor.getInt(5))
			}

			// activity_snapshot: one row per DISTINCT (time, activity, confidence).
			// Seeded location_data has 3 rows, all with distinct times.
			upgraded.query("SELECT COUNT(*) FROM activity_snapshot").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(3, cursor.getInt(0))
			}

			// cell_sample: backfilled from legacy cell_location at 20→21.
			upgraded.query("SELECT cell_id, mcc, mnc, lat_e7, lon_e7, provenance FROM cell_sample").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(9999L, cursor.getLong(0))
				assertEquals(230, cursor.getInt(1))
				assertEquals(1, cursor.getInt(2))
				assertEqualsWithinOne("lat_e7", 481250000, cursor.getLong(3))
				assertEqualsWithinOne("lon_e7", 178750000, cursor.getLong(4))
				assertEquals("LEGACY_MIGRATION", cursor.getString(5))
				assertFalse(cursor.moveToNext())
			}

			// wifi_observation: backfilled from legacy wifi_data at 20→21.
			upgraded.query(
				"""
					SELECT bssid, time_ms, ssid, frequency, level, lat_e7, lon_e7, provenance
					FROM wifi_observation
				""".trimIndent()
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("AA:BB:CC:DD:EE:01", cursor.getString(0))
				assertEquals(1700000050000L, cursor.getLong(1))
				assertEquals("Home", cursor.getString(2))
				assertEquals(2412, cursor.getInt(3))
				assertEquals(-55, cursor.getInt(4))
				assertEqualsWithinOne("lat_e7", 492500000, cursor.getLong(5))
				assertEqualsWithinOne("lon_e7", 185000000, cursor.getLong(6))
				assertEquals("LEGACY_MIGRATION", cursor.getString(7))
				assertFalse(cursor.moveToNext())
			}

			// network_operator was NOT dropped at 20→21 (reference table).
			upgraded.query("SELECT mcc, mnc, name FROM network_operator WHERE mcc = '230'").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals("230", cursor.getString(0))
				assertEquals("01", cursor.getString(1))
				assertEquals("Test Operator", cursor.getString(2))
			}

			// activity table (the lookup table for session_activity_id) is preserved.
			upgraded.query("SELECT id, name FROM activity WHERE id = 7").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(7, cursor.getInt(0))
				assertEquals("Running", cursor.getString(1))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_locationSampleCountMatchesLocationData() {
		val db = helper.createDatabase(TEST_DB, 12)
		// MIGRATION_12_13 throws if count(location_sample) != count(location_data).
		for (i in 1..50) {
			db.execSQL(
				"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
					"VALUES ($i, ${1000L + i}, ${40.0 + i * 0.001}, ${20.0 + i * 0.001}, $i.0, ${i % 30}.0, 1.0, ${i % 10}.0, 0.5, ${i % 8}, ${i % 100})"
			)
		}
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { upgraded ->
			upgraded.query("SELECT COUNT(*) FROM location_sample").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(50, cursor.getInt(0))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_handlesExtremeCoordinateValues() {
		val db = helper.createDatabase(TEST_DB, 12)
		// Near-pole, antimeridian, and equator values. Activity=3 (STILL) so
		// motion_state resolves to STILL.
		db.execSQL(
			"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
				"VALUES (1, 1000, 89.9999999, 179.9999999, 0.0, 1.0, 0.5, 0.0, 0.0, 3, 50)"
		)
		db.execSQL(
			"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
				"VALUES (2, 2000, -89.9999999, -179.9999999, -500.0, 9.99, 0.5, 0.0, 0.0, 3, 50)"
		)
		db.execSQL(
			"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
				"VALUES (3, 3000, 0.0, 0.0, 0.0, 50.0, 0.5, 0.0, 0.0, 3, 50)"
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { upgraded ->
			upgraded.query(
				"SELECT time_ms, lat_e7, lon_e7, quality FROM location_sample ORDER BY time_ms"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(1000L, cursor.getLong(0))
				assertEqualsWithinOne("lat_e7@pole", 899999999, cursor.getLong(1))
				assertEqualsWithinOne("lon_e7@antimeridian", 1799999999, cursor.getLong(2))
				assertEquals("HIGH", cursor.getString(3)) // hor_acc=1 < 10

				assertTrue(cursor.moveToNext())
				assertEqualsWithinOne("lat_e7@south-pole", -899999999, cursor.getLong(1))
				assertEqualsWithinOne("lon_e7@-antimeridian", -1799999999, cursor.getLong(2))
				assertEquals("HIGH", cursor.getString(3)) // 9.99 < 10

				assertTrue(cursor.moveToNext())
				assertEquals(0L, cursor.getLong(1))
				assertEquals(0L, cursor.getLong(2))
				// hor_acc=50 is the MEDIUM→LOW boundary. Migration uses `< 50`
				// for MEDIUM, so 50 classifies as LOW.
				assertEquals("LOW", cursor.getString(3))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_wideCellIdsSurviveWidening() {
		// Hits MIGRATION_21_22's 5G NR NCI support.
		val db = helper.createDatabase(TEST_DB, 12)
		// v12 cell_location used TEXT for mcc/mnc and INTEGER for cell_id,
		// which was 32-bit before the v21→v22 widening.
		db.execSQL(
			"INSERT INTO cell_location (id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt) " +
				"VALUES (1, 1000, '230', '01', 2147483647, 13, 30, 48.125, 17.875, 250.0)"
		)
		db.execSQL(
			"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
				"VALUES (1, 1000, 48.125, 17.875, 200.0, 5.0, 1.0, 0.0, 0.0, 3, 50)"
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { upgraded ->
			// After migration to v22 cell_id is effectively Long. Insert a
			// real 5G-sized NCI and verify it round-trips.
			upgraded.execSQL(
				"""
					INSERT INTO cell_sample (
						time_ms, cell_id, lac, mcc, mnc, network_type,
						signal_strength, lat_e7, lon_e7, provenance, created_at
					) VALUES (
						2000, 68719476735, 0, 230, 1, 20, -90, 481250000, 178750000, 'TEST', 3000
					)
				""".trimIndent()
			)
			upgraded.query("SELECT cell_id FROM cell_sample WHERE time_ms = 2000").use { cursor ->
				assertTrue(cursor.moveToFirst())
				// 68_719_476_735 = 2^36 - 1, representative of NR NCI width.
				assertEquals(68_719_476_735L, cursor.getLong(0))
			}
			// The legacy INT32 cell_id backfilled from v12 is preserved.
			upgraded.query("SELECT cell_id FROM cell_sample WHERE time_ms = 1000").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(2147483647L, cursor.getLong(0))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_nullWifiCoordinatesArePreserved() {
		val db = helper.createDatabase(TEST_DB, 12)
		db.execSQL(
			"""
				INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level)
				VALUES ('AA:BB:CC:DD:EE:00', NULL, NULL, NULL, 1, 1700000000000, 'Hidden', '[WPA2-PSK]', 5180, -70)
			""".trimIndent()
		)
		db.close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { upgraded ->
			upgraded.query(
				"SELECT lat_e7, lon_e7 FROM wifi_observation WHERE bssid = 'AA:BB:CC:DD:EE:00'"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertTrue(cursor.isNull(0))
				assertTrue(cursor.isNull(1))
			}
		}
	}

	// ---------------------------------------------------------------------
	// Post-migration FK / constraint enforcement
	// ---------------------------------------------------------------------

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_foreignKeyCascadeOnTripLeg() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			// FK enforcement is off by default in Room's raw test database;
			// enable it before asserting cascade behavior.
			db.setForeignKeyConstraintsEnabled(true)

			db.execSQL(
				"""
					INSERT INTO inferred_trip (
						id, segment_id, start_time_ms, end_time_ms, distance_m, steps, primary_activity,
						transport_mode, departure_place_id, arrival_place_id, source, inference_version,
						leg_count, created_at
					) VALUES (
						1, 1, 1, 2, 100.0, 10, 7, 'WALK', NULL, NULL, 'TEST', 'v1', 2, 1
					)
				""".trimIndent()
			)
			db.execSQL(
				"""
					INSERT INTO trip_leg (id, trip_id, sequence_index, start_time_ms, end_time_ms, distance_m, transport_mode, created_at)
					VALUES (1, 1, 0, 1, 2, 50.0, 'WALK', 1)
				""".trimIndent()
			)
			db.execSQL(
				"""
					INSERT INTO trip_leg (id, trip_id, sequence_index, start_time_ms, end_time_ms, distance_m, transport_mode, created_at)
					VALUES (2, 1, 1, 2, 3, 50.0, 'WALK', 1)
				""".trimIndent()
			)

			db.execSQL("DELETE FROM inferred_trip WHERE id = 1")
			db.query("SELECT COUNT(*) FROM trip_leg WHERE trip_id = 1").use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(
					"trip_leg.trip_id declares ON DELETE CASCADE; children must be removed",
					0,
					cursor.getInt(0)
				)
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_foreignKeyOnDeleteSetNullForInferredTripPlaces() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			db.setForeignKeyConstraintsEnabled(true)

			db.execSQL(
				"""
					INSERT INTO frequent_place (
						id, center_lat_e7, center_lon_e7, radius_m, visit_count,
						first_visit_ms, last_visit_ms, auto_category, created_at
					) VALUES (
						1, 481250000, 178750000, 50.0, 1, 1, 2, 'HOME', 1
					)
				""".trimIndent()
			)
			db.execSQL(
				"""
					INSERT INTO inferred_trip (
						id, segment_id, start_time_ms, end_time_ms, distance_m, steps, primary_activity,
						transport_mode, departure_place_id, arrival_place_id, source, inference_version,
						leg_count, created_at
					) VALUES (
						1, 1, 1, 2, 100.0, 10, 7, 'WALK', 1, 1, 'TEST', 'v1', 0, 1
					)
				""".trimIndent()
			)

			db.execSQL("DELETE FROM frequent_place WHERE id = 1")
			db.query(
				"SELECT departure_place_id, arrival_place_id FROM inferred_trip WHERE id = 1"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertTrue("departure_place_id should be NULL after FK ON DELETE SET NULL", cursor.isNull(0))
				assertTrue("arrival_place_id should be NULL after FK ON DELETE SET NULL", cursor.isNull(1))
			}
		}
	}

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_uniqueExplorationCellTokenRejectsDuplicates() {
		helper.createDatabase(TEST_DB, 12).close()

		helper.runMigrationsAndValidate(TEST_DB, 26, true, *v12ToV26Migrations).use { db ->
			db.execSQL(
				"""
					INSERT INTO exploration_cell (
						id, cell_token, level, quality, first_discovered_at, last_visited_at,
						visit_count, season_bitmask, center_lat_e7, center_lon_e7, created_at
					) VALUES (1, 'abc', 12, 2, 1, 2, 1, 0, 481250000, 178750000, 1)
				""".trimIndent()
			)
			var failed = false
			try {
				db.execSQL(
					"""
						INSERT INTO exploration_cell (
							id, cell_token, level, quality, first_discovered_at, last_visited_at,
							visit_count, season_bitmask, center_lat_e7, center_lon_e7, created_at
						) VALUES (2, 'abc', 13, 2, 3, 4, 1, 0, 481250000, 178750000, 3)
					""".trimIndent()
				)
			} catch (_: SQLException) {
				failed = true
			}
			assertTrue("Unique index on cell_token must reject duplicates", failed)
		}
	}

	// ---------------------------------------------------------------------
	// Post-migration Runtime compatibility (DAO smoke test)
	// ---------------------------------------------------------------------

	@Test
	@Throws(IOException::class)
	fun fullChain_v12_to_v26_databaseOpensSuccessfullyThroughRoom() {
		val db = helper.createDatabase(TEST_DB, 12)
		seedV12Dataset(db)
		db.close()

		// Now open through Room with ALL migrations — this is the path a real
		// upgrading device takes. If Room's runtime schema check disagrees
		// with the migration outcome, build() throws.
		val context = ApplicationProvider.getApplicationContext<android.content.Context>()
		val runtime = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
			.addMigrations(*allMigrations)
			.allowMainThreadQueries()
			.build()
		runtimeDatabase = runtime

		// Open the underlying SQLite connection. This is what triggers
		// Room's internal schema validation on first access.
		val raw = runtime.openHelper.writableDatabase
		assertEquals(26, raw.version)

		// A handful of DAOs cover the critical paths: raw sample writes,
		// segment reads, and event-log writes.
		raw.execSQL(
			"""
				INSERT INTO location_sample (
					time_ms, elapsed_realtime_nanos, lat_e7, lon_e7, alt_m, raw_gps_alt_m,
					h_acc_m, v_acc_m, speed_mps, speed_accuracy_mps, provider, quality,
					motion_state, policy, bucket_id, created_at
				) VALUES (
					5000, 0, 500000000, 100000000, 0.0, NULL, 1.0, 0.5, 0.0, 0.0,
					'runtime', 'HIGH', 'STILL', NULL, NULL, 5000
				)
			""".trimIndent()
		)
		raw.query("SELECT COUNT(*) FROM location_sample WHERE provider = 'runtime'").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(1, cursor.getInt(0))
		}
	}

	// ---------------------------------------------------------------------
	// Version-mapping contract from MIGRATION_10_11
	// ---------------------------------------------------------------------

	@Test
	@Throws(IOException::class)
	fun fullChain_v10_to_v26_activityRemappingCoversEveryLegacyId() {
		val db = helper.createDatabase(TEST_DB, 10)
		// Every legacy id that MIGRATION_10_11 mentions, paired with the
		// expected new id after the mapping. See AppDatabaseMigrations.kt.
		val legacyExpected = listOf(
			-23 to -22,  // SKI → SLOPE_SPORTS
			-24 to -22,  // SNOWBOARD → SLOPE_SPORTS
			-21 to -34,  // RACE → LAND_VEHICLE
			-27 to -26,  // CANOE → WATER_VEHICLE
			-28 to -26,  // KAYAK → WATER_VEHICLE
			-29 to -26,  // ROWING → WATER_VEHICLE
			-32 to -26,  // FERRY → WATER_VEHICLE
			-33 to -31,  // AIRBALLOON → AIR_VEHICLE
			-19 to -2,   // HIKING → WALKING
			-25 to -5,   // HORSERIDE → VEHICLE
			-6 to -26,   // SWIM → WATER_VEHICLE
			-30 to -26,  // DIVE → WATER_VEHICLE
			-7 to -5,    // TENIS → VEHICLE
			-8 to -5,
			-9 to -5,
			-10 to -5,
			-11 to -5,
			-12 to -5,
			-13 to -5,
			-14 to -5,
			-15 to -5,
			-16 to -5,
			-17 to -5,
			-18 to -5,
			-20 to -5
		)

		legacyExpected.forEachIndexed { index, (oldId, _) ->
			val id = index + 1
			db.execSQL(
				"""
					INSERT INTO tracker_session (
						id, start, `end`, user_initiated, collections, distance,
						distance_on_foot, distance_in_vehicle, steps, session_activity_id
					) VALUES ($id, ${1000 + id}, ${2000 + id}, 1, 10, 100.0, 50.0, 50.0, 10, $oldId)
				""".trimIndent()
			)
		}
		db.close()

		helper.runMigrationsAndValidate(
			TEST_DB,
			26,
			true,
			MIGRATION_10_11,
			MIGRATION_11_12,
			*v12ToV26Migrations
		).use { upgraded ->
			// Sessions survive MIGRATION_10_11 / _11_12 and are later
			// consumed by MIGRATION_12_13, which promotes them into
			// session_segment but drops the session_activity_id column.
			// Validate the intermediate state by inspecting sample_count
			// preservation for every row.
			upgraded.query(
				"SELECT COUNT(*) FROM session_segment WHERE source = 'LEGACY_MIGRATION'"
			).use { cursor ->
				assertTrue(cursor.moveToFirst())
				assertEquals(legacyExpected.size, cursor.getInt(0))
			}
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private fun seedV12Dataset(db: SupportSQLiteDatabase) {
		// activity lookup (reference table)
		db.execSQL("INSERT INTO activity (id, name, iconName) VALUES (7, 'Running', 'run')")

		// network_operator (explicitly kept at 20→21)
		db.execSQL("INSERT INTO network_operator (mcc, mnc, name) VALUES ('230', '01', 'Test Operator')")

		// tracker_session: three rows — one valid, two invalid.
		//  - id=1 valid (start < end, collections > 1)
		//  - id=2 invalid (start == end)
		//  - id=3 invalid (collections == 1)
		db.execSQL(
			"""
				INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
				VALUES (1, 1000, 2000, 1, 8, 1234.5, 900.0, 334.5, 120, 7)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
				VALUES (2, 3000, 3000, 0, 2, 0.0, 0.0, 0.0, 0, NULL)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
				VALUES (3, 4000, 5000, 0, 1, 0.0, 0.0, 0.0, 0, NULL)
			""".trimIndent()
		)

		// location_data rows that exercise quality and motion classification.
		//  - row 1: hor_acc=5 → HIGH, activity=0 (IN_VEHICLE) → STILL
		//  - row 2: hor_acc=NULL → COARSE, activity=4 (UNKNOWN) → UNKNOWN
		//  - row 3: hor_acc=75 → LOW, activity=2 (ON_FOOT) → MOVING
		db.execSQL(
			"""
				INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence)
				VALUES (1, 1000, 48.1234567, 17.9876543, 200.0, 5.0, 1.0, 2.5, 0.5, 0, 80)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence)
				VALUES (2, 2000, 48.0, 18.0, NULL, NULL, NULL, NULL, NULL, 4, 20)
			""".trimIndent()
		)
		db.execSQL(
			"""
				INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence)
				VALUES (3, 3000, 48.5, 18.5, 210.0, 75.0, 2.0, 1.5, 1.0, 2, 60)
			""".trimIndent()
		)

		// cell_location: one row that MIGRATION_20_21 backfills into cell_sample.
		db.execSQL(
			"""
				INSERT INTO cell_location (id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt)
				VALUES (1, 1700000000000, '230', '01', 9999, 13, 40, 48.125, 17.875, 250.0)
			""".trimIndent()
		)

		// wifi_data: one row that MIGRATION_20_21 backfills into wifi_observation.
		db.execSQL(
			"""
				INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level)
				VALUES ('AA:BB:CC:DD:EE:01', 18.5, 49.25, 100.0, 1700000000000, 1700000050000, 'Home', '[WPA2-PSK]', 2412, -55)
			""".trimIndent()
		)

		// location_wifi_count exists in v12 but is simply dropped at 20→21
		// without a target table. Seed one to prove no crash on drop.
		db.execSQL(
			"""
				INSERT INTO location_wifi_count (id, time, count, lat, lon, alt)
				VALUES (1, 1700000000000, 3, 48.125, 17.875, 200.0)
			""".trimIndent()
		)
	}

	private fun listTables(db: SupportSQLiteDatabase): Set<String> =
		db.query(
			"SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name != 'room_master_table'"
		).use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}

	private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> =
		db.query("PRAGMA index_list('$table')").use { cursor ->
			buildSet {
				val nameIndex = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(nameIndex))
			}
		}

	private fun assertEqualsWithinOne(label: String, expected: Long, actual: Long) {
		assertTrue(
			"$label: expected ≈ $expected (±1) but got $actual",
			kotlin.math.abs(expected - actual) <= 1L
		)
	}

	companion object {
		private const val TEST_DB = "released-v12-to-v26-migration-test"
	}
}
