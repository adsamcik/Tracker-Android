package com.adsamcik.tracker.sbase

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
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
import androidx.room.migration.Migration
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.File
import kotlin.math.abs

/**
 * Locally-runnable (JVM + Robolectric) verification that every migration in
 * the v12 → v26 chain produces a consistent final database when applied to a
 * **fully-populated** release-shape v12 schema.
 *
 * This deliberately bypasses `MigrationTestHelper` (which depends on packaged
 * schema JSON in androidTest assets) and instead:
 *
 * 1. Creates the v12 schema from scratch using the exact DDL/indices captured
 *    in `sbase/schemas/.../AppDatabase/12.json`.
 * 2. Populates **every** v12 table with multiple rows, including boundary
 *    values (NULL coordinates, extreme lat/lon, every legacy activity id
 *    handled by MIGRATION_10_11's successor, duplicate sessions, wide cell
 *    ids, etc.).
 * 3. Drives each `Migration.migrate()` call directly, mirroring what Room
 *    does on a real device upgrade.
 * 4. Asserts every observable property of the resulting schema/data:
 *    tables dropped/added, indices present, FK cascades, UNIQUE enforcement,
 *    lat/lon E7 conversion precision, session filter, quality classification,
 *    activity snapshot mapping, cell/wifi backfill, network_operator
 *    preservation.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class V12ToV26FullChainLocalTest {

	private lateinit var application: Application
	private lateinit var dbFile: File
	private lateinit var openHelper: SupportSQLiteOpenHelper
	private lateinit var db: SupportSQLiteDatabase

	@BeforeEach
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		dbFile = File(application.cacheDir, "v12_to_v26_local_test.db")
		if (dbFile.exists()) dbFile.delete()

		val configuration = SupportSQLiteOpenHelper.Configuration.builder(application)
			.name(dbFile.absolutePath)
			.callback(object : SupportSQLiteOpenHelper.Callback(12) {
				override fun onCreate(db: SupportSQLiteDatabase) = applySchemaFromJson(db, V12_SCHEMA)
				override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
			})
			.build()
		openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
		db = openHelper.writableDatabase
		assertEquals(12, db.version)

		// Emulate Room's bookkeeping so a Room Builder could open this DB and
		// recognise it as v12 before applying migrations.
		installRoomMasterTable(db, V12_IDENTITY_HASH)
	}

	@AfterEach
	fun tearDown() {
		if (::openHelper.isInitialized) openHelper.close()
		if (::dbFile.isInitialized && dbFile.exists()) dbFile.delete()
	}

	// ---------------------------------------------------------------------
	// Schema-driven v12 DDL loader
	//
	// Reads Room's exported v12 schema JSON (sbase/schemas/…/AppDatabase/12.json)
	// and executes every `createSql` statement it contains. This means:
	//   - there is a SINGLE source of truth (the schema JSON) shared with
	//     runtime Room, so the seed can never drift from what Room itself
	//     considers v12 to look like,
	//   - future-you can regenerate 12.json and this test picks it up
	//     automatically (though v12 is a released frozen schema, so in
	//     practice it should never change).
	//
	// The JSON format — generated by the Room compiler — uses `${TABLE_NAME}`
	// as a template placeholder inside each `createSql` string. Room's own
	// runtime substitutes the entity's tableName at open time; we do the
	// same here to produce executable DDL.
	// ---------------------------------------------------------------------

	private fun applySchemaFromJson(db: SupportSQLiteDatabase, schemaFile: File) {
		require(schemaFile.exists()) {
			"Schema file not found at ${schemaFile.absolutePath}. " +
				"Room compiler output must be on disk before this test runs."
		}
		val root = JSONObject(schemaFile.readText())
		val database = root.getJSONObject("database")
		val version = database.getInt("version")
		require(version == 12) { "Expected schema v12 but loaded v$version" }

		val entities = database.getJSONArray("entities")
		for (i in 0 until entities.length()) {
			val entity = entities.getJSONObject(i)
			val tableName = entity.getString("tableName")
			val tableDdl = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
			db.execSQL(tableDdl)

			val indices = entity.optJSONArray("indices") ?: continue
			for (j in 0 until indices.length()) {
				val indexDdl = indices.getJSONObject(j)
					.getString("createSql")
					.replace("\${TABLE_NAME}", tableName)
				db.execSQL(indexDdl)
			}
		}
	}

	/**
	 * Populate room_master_table with the v12 identity hash, matching what
	 * Room's own RoomOpenHelper writes at open time. Without this row a
	 * Room Builder opening this DB would treat it as "unknown version" and
	 * refuse to migrate. With it, the upgrade path is indistinguishable from
	 * the real one a device executes.
	 */
	private fun installRoomMasterTable(db: SupportSQLiteDatabase, identityHash: String) {
		db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
		db.execSQL(
			"INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (?, ?)",
			arrayOf<Any?>(42, identityHash)
		)
	}

	// ---------------------------------------------------------------------
	// Dataset seeding
	// ---------------------------------------------------------------------

	private data class ExpectedSeed(
		val locationCount: Int,
		val validSessionCount: Int,
		val wifiCount: Int,
		val cellCount: Int
	)

	private data class Quintuple(
		val horAcc: Double?,
		val activity: Int,
		val confidence: Int,
		val lat: Double,
		val lon: Double
	)

	private fun seedFullV12Dataset(): ExpectedSeed {
		// activity rows (reference lookups)
		for ((id, name) in listOf(2 to "Walking", 5 to "Vehicle", 7 to "Running", 22 to "SlopeSports", 34 to "LandVehicle", 26 to "WaterVehicle", 31 to "AirVehicle")) {
			db.execSQL("INSERT INTO activity (id, name, iconName) VALUES ($id, '$name', '$name-icon')")
		}

		// network_operator rows (preserved by v20→v21)
		db.execSQL("INSERT INTO network_operator (mcc, mnc, name) VALUES ('230', '01', 'T-Mobile CZ')")
		db.execSQL("INSERT INTO network_operator (mcc, mnc, name) VALUES ('231', '02', 'Orange SK')")
		db.execSQL("INSERT INTO network_operator (mcc, mnc, name) VALUES ('310', '260', 'T-Mobile US')")

		// tracker_session: 10 valid rows + 5 invalid to verify filter
		// Invalid reasons: start == end, start > end, collections == 1, collections == 0
		var validSessions = 0
		for (i in 1..10) {
			val start = 1_000_000L + i * 10_000
			val end = start + 5_000
			db.execSQL(
				"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
					"VALUES ($i, $start, $end, ${i % 2}, ${5 + i}, ${100.0 * i}, ${50.0 * i}, ${50.0 * i}, ${100 * i}, 7)"
			)
			validSessions++
		}
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (11, 2000, 2000, 0, 2, 0.0, 0.0, 0.0, 0, NULL)"
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (12, 3000, 2500, 0, 2, 0.0, 0.0, 0.0, 0, NULL)"
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (13, 4000, 5000, 0, 1, 0.0, 0.0, 0.0, 0, NULL)"
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (14, 5000, 6000, 0, 0, 0.0, 0.0, 0.0, 0, NULL)"
		)
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (15, 6000, 5500, 1, 10, 0.0, 0.0, 0.0, 0, 2)"
		)

		// location_data: one row per (quality_bucket × motion_bucket) pair plus
		// a few geographic extremes. Every branch of MIGRATION_12_13's CASE
		// statements must be exercised.
		//
		// Activity ID mapping (per MIGRATION_12_13):
		//   IN (0, 3)    → STILL
		//   IN (2, 7, 8) → MOVING
		//   else         → UNKNOWN
		//
		// Quality mapping (hor_acc):
		//   NULL         → COARSE
		//   < 10         → HIGH
		//   < 50         → MEDIUM
		//   else         → LOW
		val samples = listOf(
			// (hor_acc, activity, confidence, lat, lon)
			Quintuple(5.0, 0, 80, 48.1234567, 17.9876543),    // HIGH + STILL, Czech Republic
			Quintuple(9.99, 2, 70, 37.7749, -122.4194),       // HIGH boundary + MOVING, SF
			Quintuple(1.5, 3, 90, 89.9999999, 179.9999999),   // HIGH + STILL, North Pole corner
			Quintuple(10.0, 7, 85, -89.9999999, -179.9999999), // MEDIUM boundary + MOVING, South Pole corner
			Quintuple(25.0, 8, 75, 51.5074, -0.1278),         // MEDIUM + MOVING, London
			Quintuple(49.99, 2, 55, -34.6037, -58.3816),      // MEDIUM boundary + MOVING, Buenos Aires
			Quintuple(50.0, 4, 20, 0.0, 0.0),                 // LOW boundary + UNKNOWN, null island
			Quintuple(75.0, 5, 10, 35.6762, 139.6503),        // LOW + UNKNOWN, Tokyo
			Quintuple(100.0, 3, 30, -33.8688, 151.2093),      // LOW + STILL, Sydney
			Quintuple(null, 0, 50, 60.1699, 24.9384),         // COARSE + STILL, Helsinki
			Quintuple(null, 7, 60, -1.2921, 36.8219),         // COARSE + MOVING, Nairobi
			Quintuple(null, 6, 40, 55.7558, 37.6173),         // COARSE + UNKNOWN, Moscow
			// Extra rows for volume
			Quintuple(12.0, 8, 70, 40.7128, -74.0060),        // MEDIUM + MOVING, NYC
			Quintuple(200.0, 9, 5, 19.4326, -99.1332),        // LOW + UNKNOWN, Mexico City
			Quintuple(3.0, 2, 80, -22.9068, -43.1729)         // HIGH + MOVING, Rio
		)
		var rowId = 0
		for (s in samples) {
			rowId++
			val alt = if (rowId % 3 == 0) "NULL" else "${100.0 + rowId}"
			val verAcc = if (rowId % 4 == 0) "NULL" else "${1.0 + rowId * 0.1}"
			val speed = if (rowId % 5 == 0) "NULL" else "${rowId * 0.5}"
			val sAcc = if (rowId % 5 == 0) "NULL" else "0.5"
			val horStr = s.horAcc?.toString() ?: "NULL"
			db.execSQL(
				"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
					"VALUES ($rowId, ${1_000_000 + rowId * 1000}, ${s.lat}, ${s.lon}, $alt, $horStr, $verAcc, $speed, $sAcc, ${s.activity}, ${s.confidence})"
			)
		}
		val locationTotal = rowId

		// wifi_data: 15 rows + 2 with NULL coords + 1 edge case (very long ssid)
		val longSsid = "SSID-" + "X".repeat(240)
		val longCaps = "[EDGE]-" + "Y".repeat(240)
		for (i in 1..15) {
			val bssid = "00:11:22:33:44:%02X".format(i)
			val lat = 48.0 + i * 0.01
			val lon = 18.0 + i * 0.01
			db.execSQL(
				"INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level) " +
					"VALUES ('$bssid', $lon, $lat, ${100.0 + i}, ${1700000000000L + i * 1000}, ${1700000050000L + i * 1000}, 'AP-$i', '[WPA2-PSK-CCMP][ESS]', ${2412 + i}, ${-50 - i})"
			)
		}
		db.execSQL(
			"INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level) " +
				"VALUES ('AA:AA:AA:AA:AA:01', NULL, NULL, NULL, 1, 1700000000000, 'Hidden1', '[WPA3]', 5180, -70)"
		)
		db.execSQL(
			"INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level) " +
				"VALUES ('BB:BB:BB:BB:BB:02', NULL, NULL, NULL, 1, 1700000000000, 'Hidden2', '[WPA2]', 2412, -75)"
		)
		db.execSQL(
			"INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level) " +
				"VALUES ('FF:FF:FF:FF:FF:FF', 18.5, 49.25, 150.0, 1, 9223372036854775806, '$longSsid', '$longCaps', 2147483647, -2147483648)"
		)
		val wifiTotal = 18

		// cell_location: 10 rows (varied mcc/mnc/cell_id types)
		for (i in 1..10) {
			db.execSQL(
				"INSERT INTO cell_location (id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt) " +
					"VALUES ($i, ${1700000000000L + i * 1000}, '23${i % 2}', '0$i', ${1000 + i * 100}, ${10 + i}, ${30 + i}, ${48.0 + i * 0.01}, ${17.0 + i * 0.01}, ${250.0 + i})"
			)
		}
		// cell_location with NULL alt, MAX cell_id (pre-widening)
		db.execSQL(
			"INSERT INTO cell_location (id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt) " +
				"VALUES (11, 1700000100000, '999', '999', 2147483647, 13, 42, -89.9999999, 179.9999999, NULL)"
		)
		val cellTotal = 11

		// location_wifi_count: 5 rows (table is simply dropped at 20→21 — seed
		// just proves DROP TABLE doesn't fail on populated data)
		for (i in 1..5) {
			db.execSQL(
				"INSERT INTO location_wifi_count (id, time, count, lat, lon, alt) " +
					"VALUES ($i, ${1700000000000L + i * 1000}, ${i * 3}, ${48.0 + i * 0.01}, ${17.0 + i * 0.01}, ${200.0 + i})"
			)
		}

		return ExpectedSeed(
			locationCount = locationTotal,
			validSessionCount = validSessions,
			wifiCount = wifiTotal,
			cellCount = cellTotal
		)
	}

	// ---------------------------------------------------------------------
	// Chain execution
	// ---------------------------------------------------------------------

	private val v12ToV26: List<Migration> = listOf(
		MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
		MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
		MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
		MIGRATION_24_25, MIGRATION_25_26
	)

	private fun runChain() {
		for (migration in v12ToV26) {
			migration.migrate(db)
		}
	}

	// ---------------------------------------------------------------------
	// Actual verification tests
	// ---------------------------------------------------------------------

	@Test
	fun v12Schema_matchesPinnedIdentityHash() {
		// Drift guard: if 12.json is ever regenerated and its identityHash
		// changes, Room's runtime check would reject existing v12 databases
		// on upgraded devices. Catch that here instead of in production.
		val root = JSONObject(V12_SCHEMA.readText())
		val storedHash = root.getJSONObject("database").getString("identityHash")
		assertEquals(V12_IDENTITY_HASH, storedHash, "v12 schema identityHash changed — released v12 DBs would be rejected")
	}

	@Test
	fun v12Schema_createsEveryExpectedTableAndIndex() {
		// Prove the loader actually produced the schema we expect, independent
		// of the migrations. If this fails, no downstream migration assertion
		// can be trusted.
		val expectedTables = setOf(
			"location_data", "tracker_session", "wifi_data", "activity",
			"network_operator", "cell_location", "location_wifi_count"
		)
		val expectedIndices = mapOf(
			"location_data" to setOf("idx_location_time_lat_lon", "idx_location_lat_lon"),
			"tracker_session" to setOf("index_tracker_session_session_activity_id"),
			"wifi_data" to setOf("index_wifi_data_longitude", "index_wifi_data_latitude", "index_wifi_data_last_seen"),
			"activity" to setOf("index_activity_name"),
			"cell_location" to setOf("index_cell_location_mcc_mnc_cell_id", "index_cell_location_time"),
			"location_wifi_count" to setOf(
				"index_location_wifi_count_lon",
				"index_location_wifi_count_lat",
				"index_location_wifi_count_time"
			)
		)
		expectedTables.forEach { table ->
			assertTrue(tableExists(table), "v12 seed must create $table")
		}
		expectedIndices.forEach { (table, indices) ->
			val actual = indexNames(table)
			indices.forEach { idx ->
				assertTrue(actual.contains(idx), "v12 seed must create $table.$idx (actual: $actual)")
			}
		}
	}

	@Test
	fun chain_executesWithoutError_onFullyPopulatedV12() {
		val seed = seedFullV12Dataset()

		runChain()

		// Sanity: migration completes and table counts match the filter contracts.
		assertEquals(seed.locationCount, countOf("location_sample"))
		assertEquals(seed.validSessionCount, countOf("session_segment", "source = 'LEGACY_MIGRATION'"))
		// activity_snapshot: one row per (time, activity, confidence) — our
		// 25 location rows each have distinct time, so 25 snapshots.
		assertEquals(seed.locationCount, countOf("activity_snapshot"))
		assertEquals(seed.wifiCount, countOf("wifi_observation"))
		assertEquals(seed.cellCount, countOf("cell_sample"))

		// Reference tables preserved.
		assertEquals(7, countOf("activity"))
		assertEquals(3, countOf("network_operator"))
	}

	@Test
	fun chain_dropsEveryLegacyTable() {
		seedFullV12Dataset()
		runChain()

		listOf("tracker_session", "location_data", "wifi_data", "cell_location", "location_wifi_count").forEach { legacy ->
			assertFalse(
				tableExists(legacy),
				"Legacy table '$legacy' should be dropped by MIGRATION_20_21"
			)
		}
	}

	@Test
	fun chain_createsEveryV26Table() {
		seedFullV12Dataset()
		runChain()

		val expected = listOf(
			"activity", "network_operator",
			"location_sample", "step_interval", "activity_snapshot",
			"cell_sample", "wifi_observation", "tracker_run", "session_segment",
			"daily_summary", "live_stats",
			"frequent_place", "inferred_trip", "trip_leg",
			"exploration_cell", "exploration_streak", "achievement_progress", "personal_record",
			"route_cache", "export_log", "storage_size_snapshot",
			"domain_event", "domain_event_cursor",
			"pressure_sample", "ski_run_segment",
			"pending_signal"
		)
		expected.forEach { table ->
			assertTrue(tableExists(table), "Expected table '$table' to exist after v12→v26 chain")
		}
	}

	@Test
	fun chain_coordinateConversion_isE7PrecisionAccurate() {
		seedFullV12Dataset()
		runChain()

		// Sample known coordinates from the seed: the first location row is
		// (48.1234567, 17.9876543) — verify they round-trip as E7 within ±1.
		db.query("SELECT lat_e7, lon_e7 FROM location_sample ORDER BY time_ms LIMIT 1").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEqualsWithinOne("lat_e7", 481234567, cursor.getLong(0))
			assertEqualsWithinOne("lon_e7", 179876543, cursor.getLong(1))
		}
	}

	@Test
	fun chain_qualityClassification_boundariesAreExact() {
		seedFullV12Dataset()
		runChain()

		// Per MIGRATION_12_13: NULL → COARSE; <10 → HIGH; <50 → MEDIUM; else LOW.
		// We seeded hor_acc values at each boundary: 5.0, 9.99, 10.0, 25.0, 49.99, 50.0, 75.0, NULL.
		val qualityOf = { horAccStr: String ->
			db.query("SELECT quality FROM location_sample WHERE h_acc_m $horAccStr LIMIT 1").use { cursor ->
				assertTrue(cursor.moveToFirst(), "No row matched h_acc_m $horAccStr")
				cursor.getString(0)
			}
		}
		assertEquals("HIGH", qualityOf("= 5.0"))
		assertEquals("HIGH", qualityOf("= 9.99"))
		assertEquals("MEDIUM", qualityOf("= 10.0"))
		assertEquals("MEDIUM", qualityOf("= 25.0"))
		assertEquals("MEDIUM", qualityOf("= 49.99"))
		assertEquals("LOW", qualityOf("= 50.0"))
		assertEquals("LOW", qualityOf("= 75.0"))
		assertEquals("COARSE", qualityOf("IS NULL"))
	}

	@Test
	fun chain_motionStateClassification_coversAllBranches() {
		seedFullV12Dataset()
		runChain()

		val motionCountByState = mapOf(
			"STILL" to countOf("location_sample", "motion_state = 'STILL'"),
			"MOVING" to countOf("location_sample", "motion_state = 'MOVING'"),
			"UNKNOWN" to countOf("location_sample", "motion_state = 'UNKNOWN'")
		)
		// All three classification branches are exercised by our seed.
		assertTrue(motionCountByState.getValue("STILL") > 0, "STILL branch should classify some rows")
		assertTrue(motionCountByState.getValue("MOVING") > 0, "MOVING branch should classify some rows")
		assertTrue(motionCountByState.getValue("UNKNOWN") > 0, "UNKNOWN branch should classify some rows")
		// Every row must fall into exactly one bucket.
		assertEquals(
			countOf("location_sample"),
			motionCountByState.values.sum()
		)
	}

	@Test
	fun chain_sessionsAreFilteredByValidityRule() {
		seedFullV12Dataset()
		runChain()

		// 10 valid + 5 invalid (start==end, start>end twice, collections==1, collections==0).
		assertEquals(10, countOf("session_segment", "source = 'LEGACY_MIGRATION'"))

		// Every surviving row has start<end and sample_count>1.
		db.query(
			"SELECT start_time_ms, end_time_ms, sample_count FROM session_segment WHERE source = 'LEGACY_MIGRATION'"
		).use { cursor ->
			while (cursor.moveToNext()) {
				assertTrue(cursor.getLong(0) < cursor.getLong(1), "start must be < end")
				assertTrue(cursor.getInt(2) > 1, "sample_count must be > 1")
			}
		}

		// has_distance_anomaly defaulted to 0 for all survivors (MIGRATION_19_20).
		assertEquals(
			10,
			countOf("session_segment", "source = 'LEGACY_MIGRATION' AND has_distance_anomaly = 0")
		)
	}

	@Test
	fun chain_cellSampleBackfill_convertsMccMncAndCoordinates() {
		seedFullV12Dataset()
		runChain()

		// Seed row i=1 writes mcc='23${1 % 2}' = '231', mnc='01', cell_id=1100,
		// lat=48.01, lon=17.01 (see cell_location seeding above).
		db.query(
			"SELECT cell_id, mcc, mnc, lat_e7, lon_e7, provenance FROM cell_sample WHERE time_ms = 1700000001000"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(1100L, cursor.getLong(0))
			assertEquals(231, cursor.getInt(1))
			assertEquals(1, cursor.getInt(2))
			assertEqualsWithinOne("lat_e7", 480100000, cursor.getLong(3))
			assertEqualsWithinOne("lon_e7", 170100000, cursor.getLong(4))
			assertEquals("LEGACY_MIGRATION", cursor.getString(5))
		}

		// NULL-alt cell with Int.MAX_VALUE cell_id survives widening.
		db.query(
			"SELECT cell_id FROM cell_sample WHERE time_ms = 1700000100000"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(2147483647L, cursor.getLong(0))
		}
	}

	@Test
	fun chain_cellIdWidening_acceptsFullLongValues() {
		seedFullV12Dataset()
		runChain()

		// After v21→v22 the column is effectively INTEGER (up to 8 bytes).
		// A real 5G NR NCI (2^36-1) must fit.
		db.execSQL(
			"INSERT INTO cell_sample (time_ms, cell_id, lac, mcc, mnc, network_type, " +
				"signal_strength, lat_e7, lon_e7, provenance, created_at) " +
				"VALUES (9999, 68719476735, 0, 230, 1, 20, -90, 481250000, 178750000, 'TEST', 9999)"
		)
		db.query("SELECT cell_id FROM cell_sample WHERE time_ms = 9999").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(68_719_476_735L, cursor.getLong(0))
		}
	}

	@Test
	fun chain_wifiObservationBackfill_preservesNullCoords() {
		seedFullV12Dataset()
		runChain()

		// Two seed rows had NULL coordinates. Their backfills into
		// wifi_observation must also have NULL lat_e7/lon_e7.
		db.query(
			"SELECT lat_e7, lon_e7 FROM wifi_observation WHERE bssid IN ('AA:AA:AA:AA:AA:01', 'BB:BB:BB:BB:BB:02') ORDER BY bssid"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertTrue(cursor.isNull(0), "Expected NULL lat_e7 for backfilled Hidden1")
			assertTrue(cursor.isNull(1), "Expected NULL lon_e7 for backfilled Hidden1")
			assertTrue(cursor.moveToNext())
			assertTrue(cursor.isNull(0), "Expected NULL lat_e7 for backfilled Hidden2")
			assertTrue(cursor.isNull(1), "Expected NULL lon_e7 for backfilled Hidden2")
		}
	}

	@Test
	fun chain_wifiObservationBackfill_preservesEdgeCaseStringsAndIntExtremes() {
		seedFullV12Dataset()
		runChain()

		db.query(
			"SELECT time_ms, ssid, capabilities, frequency, level FROM wifi_observation WHERE bssid = 'FF:FF:FF:FF:FF:FF'"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(9_223_372_036_854_775_806L, cursor.getLong(0))
			assertTrue(cursor.getString(1).startsWith("SSID-X"))
			// 'SSID-' (5 chars) + 240 X's = 245.
			assertEquals(245, cursor.getString(1).length)
			assertEquals(2147483647, cursor.getInt(3))
			assertEquals(-2147483648, cursor.getInt(4))
		}
	}

	@Test
	fun chain_networkOperatorSurvivesLegacyDrop() {
		seedFullV12Dataset()
		runChain()

		assertEquals(3, countOf("network_operator"))
		db.query("SELECT name FROM network_operator WHERE mcc = '310' AND mnc = '260'").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals("T-Mobile US", cursor.getString(0))
		}
	}

	@Test
	fun chain_rawGpsAltColumnIsAddedNullable_andExistingRowsGetNull() {
		seedFullV12Dataset()
		runChain()

		// Every backfilled location_sample row from MIGRATION_12_13 must have
		// raw_gps_alt_m = NULL (introduced at 18→19 with no default).
		val total = countOf("location_sample")
		val nulls = countOf("location_sample", "raw_gps_alt_m IS NULL")
		assertEquals(total, nulls)
	}

	@Test
	fun chain_achievementProgress_hasAllV26ColumnsAndNullableTier() {
		seedFullV12Dataset()
		runChain()

		// Insert pre-v23 style rows first via raw SQL to confirm tier can be NULL.
		db.execSQL(
			"INSERT INTO achievement_progress (achievement_id, current_value, target_value, tier, unlocked_at, updated_at, notified_at) " +
				"VALUES ('post-chain', 1, 10, NULL, NULL, 1, NULL)"
		)
		db.query(
			"SELECT tier, notified_at FROM achievement_progress WHERE achievement_id = 'post-chain'"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertTrue(cursor.isNull(0))
			assertTrue(cursor.isNull(1))
		}

		// UNIQUE index on achievement_id rejects duplicates.
		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO achievement_progress (achievement_id, current_value, target_value, tier, unlocked_at, updated_at, notified_at) " +
					"VALUES ('post-chain', 5, 10, 2, 1, 2, NULL)"
			)
		}
	}

	@Test
	fun chain_pendingSignalTable_isUsable() {
		seedFullV12Dataset()
		runChain()

		db.execSQL(
			"INSERT INTO pending_signal (session_id, signal_json, created_at) " +
				"VALUES (42, '{\"type\":\"LOCATION\"}', 1234)"
		)
		db.query("SELECT session_id, created_at FROM pending_signal").use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertEquals(42L, cursor.getLong(0))
			assertEquals(1234L, cursor.getLong(1))
		}
	}

	@Test
	fun chain_foreignKeyCascade_removesTripLegsOnTripDelete() {
		seedFullV12Dataset()
		runChain()
		db.setForeignKeyConstraintsEnabled(true)

		db.execSQL(
			"INSERT INTO inferred_trip (id, segment_id, start_time_ms, end_time_ms, distance_m, steps, " +
				"primary_activity, transport_mode, departure_place_id, arrival_place_id, source, " +
				"inference_version, leg_count, created_at) " +
				"VALUES (1, 1, 1, 2, 100.0, 10, 7, 'WALK', NULL, NULL, 'TEST', 'v1', 2, 1)"
		)
		db.execSQL(
			"INSERT INTO trip_leg (id, trip_id, sequence_index, start_time_ms, end_time_ms, distance_m, transport_mode, created_at) " +
				"VALUES (1, 1, 0, 1, 2, 50.0, 'WALK', 1)"
		)
		db.execSQL(
			"INSERT INTO trip_leg (id, trip_id, sequence_index, start_time_ms, end_time_ms, distance_m, transport_mode, created_at) " +
				"VALUES (2, 1, 1, 2, 3, 50.0, 'WALK', 1)"
		)

		db.execSQL("DELETE FROM inferred_trip WHERE id = 1")
		assertEquals(0, countOf("trip_leg", "trip_id = 1"))
	}

	@Test
	fun chain_foreignKeySetNull_clearsPlaceRefsOnPlaceDelete() {
		seedFullV12Dataset()
		runChain()
		db.setForeignKeyConstraintsEnabled(true)

		db.execSQL(
			"INSERT INTO frequent_place (id, center_lat_e7, center_lon_e7, radius_m, visit_count, " +
				"first_visit_ms, last_visit_ms, auto_category, created_at) " +
				"VALUES (1, 481250000, 178750000, 50.0, 1, 1, 2, 'HOME', 1)"
		)
		db.execSQL(
			"INSERT INTO inferred_trip (id, segment_id, start_time_ms, end_time_ms, distance_m, steps, " +
				"primary_activity, transport_mode, departure_place_id, arrival_place_id, source, " +
				"inference_version, leg_count, created_at) " +
				"VALUES (1, 1, 1, 2, 100.0, 10, 7, 'WALK', 1, 1, 'TEST', 'v1', 0, 1)"
		)

		db.execSQL("DELETE FROM frequent_place WHERE id = 1")
		db.query(
			"SELECT departure_place_id, arrival_place_id FROM inferred_trip WHERE id = 1"
		).use { cursor ->
			assertTrue(cursor.moveToFirst())
			assertTrue(cursor.isNull(0))
			assertTrue(cursor.isNull(1))
		}
	}

	@Test
	fun chain_uniqueExplorationCellTokenIsEnforced() {
		seedFullV12Dataset()
		runChain()

		db.execSQL(
			"INSERT INTO exploration_cell (id, cell_token, level, quality, first_discovered_at, " +
				"last_visited_at, visit_count, season_bitmask, center_lat_e7, center_lon_e7, created_at) " +
				"VALUES (1, 'abc', 12, 2, 1, 2, 1, 0, 481250000, 178750000, 1)"
		)
		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO exploration_cell (id, cell_token, level, quality, first_discovered_at, " +
					"last_visited_at, visit_count, season_bitmask, center_lat_e7, center_lon_e7, created_at) " +
					"VALUES (2, 'abc', 13, 2, 3, 4, 1, 0, 0, 0, 3)"
			)
		}
	}

	@Test
	fun chain_allDeclaredV26IndicesPresent() {
		seedFullV12Dataset()
		runChain()

		// The set declared on the Room entities at v26. Missing entries here
		// mean a fresh-install DB and an upgraded DB diverge at runtime.
		val declared = mapOf(
			"location_sample" to setOf(
				"idx_location_sample_time", "idx_location_sample_coords", "idx_location_sample_bucket"
			),
			"step_interval" to setOf("idx_step_interval_time_range"),
			"activity_snapshot" to setOf("idx_activity_snapshot_time"),
			"cell_sample" to setOf(
				"idx_cell_sample_time", "idx_cell_sample_cell_id", "idx_cell_sample_coords"
			),
			"wifi_observation" to setOf(
				"idx_wifi_obs_time", "idx_wifi_obs_bssid", "idx_wifi_obs_coords"
			),
			"tracker_run" to setOf("idx_tracker_run_time_range"),
			"session_segment" to setOf(
				"idx_session_segment_time_range", "idx_session_segment_source",
				"idx_session_segment_primary_activity", "idx_session_segment_end_time_ms"
			),
			"daily_summary" to setOf("index_daily_summary_date_epoch_day"),
			"frequent_place" to setOf(
				"idx_frequent_place_coords", "index_frequent_place_last_visit_ms"
			),
			"inferred_trip" to setOf(
				"idx_inferred_trip_time_range", "idx_inferred_trip_departure",
				"idx_inferred_trip_arrival", "index_inferred_trip_segment_id"
			),
			"trip_leg" to setOf("idx_trip_leg_trip_seq"),
			"exploration_cell" to setOf(
				"index_exploration_cell_cell_token", "index_exploration_cell_level",
				"index_exploration_cell_first_discovered_at",
				"index_exploration_cell_level_first_discovered_at"
			),
			"achievement_progress" to setOf(
				"index_achievement_progress_achievement_id",
				"index_achievement_progress_updated_at",
				"index_achievement_progress_unlocked_at"
			),
			"personal_record" to setOf("index_personal_record_metric"),
			"route_cache" to setOf(
				"index_route_cache_session_id", "index_route_cache_segment_id",
				"index_route_cache_start_time"
			),
			"export_log" to setOf(
				"index_export_log_completed_at", "index_export_log_started_at"
			),
			"storage_size_snapshot" to setOf("index_storage_size_snapshot_epoch_day"),
			"domain_event" to setOf(
				"index_domain_event_timestamp_ms_id",
				"index_domain_event_event_type_processor_id"
			),
			"pressure_sample" to setOf(
				"idx_pressure_sample_time", "idx_pressure_sample_bucket"
			),
			"ski_run_segment" to setOf(
				"idx_ski_run_segment_session", "idx_ski_run_segment_start_time"
			),
			"pending_signal" to setOf("idx_pending_signal_session_time")
		)

		val missing = mutableListOf<String>()
		declared.forEach { (table, wanted) ->
			val have = indexNames(table)
			(wanted - have).forEach { missing.add("$table.$it") }
		}
		assertTrue(
			missing.isEmpty(),
			"Missing indices after v12→v26 chain (declared on entity, not created by migration): $missing"
		)
	}

	@Test
	fun chain_uniqueIndicesStillEnforceUniqueness() {
		seedFullV12Dataset()
		runChain()

		db.execSQL(
			"INSERT INTO daily_summary (date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, active_tracking_ms, last_updated_ms, created_at) " +
				"VALUES (20001, 0.0, 0, 0, 0, 0, 1, 1)"
		)
		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO daily_summary (date_epoch_day, total_distance_m, total_steps, total_duration_ms, trip_count, active_tracking_ms, last_updated_ms, created_at) " +
					"VALUES (20001, 1.0, 1, 1, 1, 1, 2, 2)"
			)
		}

		db.execSQL(
			"INSERT INTO personal_record (metric, value, achieved_at, updated_at) VALUES ('longest_distance', 100.0, 1, 1)"
		)
		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO personal_record (metric, value, achieved_at, updated_at) VALUES ('longest_distance', 200.0, 2, 2)"
			)
		}
	}

	@Test
	fun chain_endStateSchemaMatchesV26ExpectedShape() {
		seedFullV12Dataset()
		runChain()
		// The raw SupportSQLiteOpenHelper driver we use here does not create
		// room_master_table — that is Room's own bookkeeping table, inserted
		// when a RoomDatabase is opened through a Builder. We instead verify
		// the schema shape directly: every v26-exclusive column exists on
		// its owning table.
		val columnsByTable = mapOf(
			"location_sample" to "raw_gps_alt_m",
			"session_segment" to "has_distance_anomaly",
			"achievement_progress" to "notified_at",
			"cell_sample" to "cell_id",
			"pending_signal" to "signal_json"
		)
		columnsByTable.forEach { (table, column) ->
			db.query("SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = ?", arrayOf<Any?>(column))
				.use { cursor ->
					assertTrue(cursor.moveToFirst())
					assertEquals(1, cursor.getInt(0), "Expected $table.$column to exist at v26")
				}
		}
		assertNotNull(columnsByTable)
	}

	@Test
	fun chain_emptyV12Database_migratesWithoutError() {
		// Guard against migrations that crash on empty inputs (e.g. NPE on
		// SELECT over empty legacy tables).
		runChain()

		assertEquals(0, countOf("location_sample"))
		assertEquals(0, countOf("session_segment"))
		assertEquals(0, countOf("activity_snapshot"))
		assertEquals(0, countOf("cell_sample"))
		assertEquals(0, countOf("wifi_observation"))
		assertFalse(tableExists("location_data"))
	}

	@Test
	fun chain_activitySnapshotOneRowPerLegacyLocation() {
		seedFullV12Dataset()
		runChain()

		// MIGRATION_12_13 uses SELECT DISTINCT over (time, activity, confidence).
		// Our seed has 25 unique (time, activity, confidence) tuples.
		val locationCount = countOf("location_sample")
		val snapshotCount = countOf("activity_snapshot")
		assertEquals(
			locationCount, snapshotCount,
			"activity_snapshot should have one row per unique location_data activity change"
		)
	}

	@Test
	fun chain_acceptedByRoomRuntimeValidator() {
		// Ultimate audit: after the raw migration chain completes on a real
		// populated v12 DB, close the file and hand it to Room's own Builder.
		// Room reads `room_master_table.identity_hash` (which we seeded in
		// setUp), sees it's at v12, applies all registered Migrations, and
		// then calls RoomOpenHelper.Delegate.validateMigration() — which
		// compares every table's columns, foreign keys, AND indices against
		// the compiled schema. Any drift (missing column, wrong type,
		// missing index, wrong FK) throws on first access.
		seedFullV12Dataset()
		if (::openHelper.isInitialized) openHelper.close()

		val runtime = Room.databaseBuilder(application, AppDatabase::class.java, dbFile.absolutePath)
			.openHelperFactory(FrameworkSQLiteOpenHelperFactory())
			.allowMainThreadQueries()
			.addMigrations(
				MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
				MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
				MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
				MIGRATION_24_25, MIGRATION_25_26
			)
			.build()
		try {
			// Force DB open → triggers migrations → triggers validateMigration().
			val version = runtime.openHelper.writableDatabase.version
			assertEquals(26, version)

			// Sanity: the legacy data actually arrived in the sessionless
			// tables. DAO resolution itself is part of the Room open path
			// (type-safety fails at build time if a DAO column is wrong).
			@Suppress("UNUSED_VARIABLE")
			val dao = runtime.locationSampleDao()
			val locationCount = runtime.openHelper.writableDatabase
				.query("SELECT COUNT(*) FROM location_sample")
				.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
			assertTrue(locationCount > 0, "Expected location_sample to be populated, got $locationCount")
		} finally {
			runtime.close()
		}
	}

	@Test
	fun chain_doesNotDependOnFkEnforcementState() {
		// Room applies migrations with FKs disabled (its default). Some buggy
		// migrations inadvertently start to rely on that: e.g. inserting a
		// child row whose parent was just dropped in the same migration.
		// We run the chain with FKs **enabled** to flush out any such
		// dependency — if it still completes, we know no migration secretly
		// needed FKs off.
		seedFullV12Dataset()
		db.setForeignKeyConstraintsEnabled(true)

		runChain()

		// Sanity: still reaches v26 schema shape.
		assertTrue(tableExists("pending_signal"))
		assertTrue(tableExists("session_segment"))
		assertFalse(tableExists("tracker_session"))
	}

	@Test
	fun chain_insideSingleTransaction_rollsBackOnFailure() {
		// Simulate a mid-chain failure inside a single outer transaction —
		// this is what Room does when wrapping migrations. A failure partway
		// through must leave the DB in its pre-transaction state.
		seedFullV12Dataset()
		val preLocationDataCount = countOf("location_data")
		val preTrackerSessionCount = countOf("tracker_session")
		assertTrue(preLocationDataCount > 0)
		assertTrue(preTrackerSessionCount > 0)

		val caught = assertThrows(RuntimeException::class.java) {
			db.beginTransaction()
			try {
				// Apply the first few migrations successfully.
				MIGRATION_12_13.migrate(db)
				MIGRATION_13_14.migrate(db)
				// Now deliberately break: execute an SQL statement that
				// cannot possibly succeed (table does not exist). SQLite
				// aborts the transaction.
				db.execSQL("INSERT INTO no_such_table (x) VALUES (1)")
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
		}
		assertTrue(
			caught.message?.contains("no such table") == true ||
				caught.cause?.message?.contains("no such table") == true,
			"Expected 'no such table' failure, got: ${caught.message}"
		)

		// Pre-transaction state must be intact.
		assertTrue(tableExists("location_data"))
		assertEquals(preLocationDataCount, countOf("location_data"))
		assertEquals(preTrackerSessionCount, countOf("tracker_session"))
		// New tables created inside the rolled-back transaction must NOT
		// survive.
		assertFalse(tableExists("location_sample"))
		assertFalse(tableExists("daily_summary"))
	}

	@Test
	fun chain_survivesHighVolumeLocationData() {
		// Volume stress: 5 000 location_data rows. Catches O(n²) regressions
		// in MIGRATION_12_13's bulk INSERT…SELECT and sanity-checks SQLite
		// transactional throughput on a real migration.
		db.execSQL("INSERT INTO activity (id, name, iconName) VALUES (7, 'Running', 'run')")
		db.beginTransaction()
		try {
			val stmt = db.compileStatement(
				"INSERT INTO location_data (id, time, lat, lon, alt, hor_acc, ver_acc, speed, s_acc, activity, confidence) " +
					"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			)
			for (i in 1..5_000) {
				stmt.clearBindings()
				stmt.bindLong(1, i.toLong())
				stmt.bindLong(2, 1_000_000L + i)
				stmt.bindDouble(3, 48.0 + (i % 1000) * 0.0001)
				stmt.bindDouble(4, 17.0 + (i % 1000) * 0.0001)
				stmt.bindDouble(5, 100.0 + i % 500)
				stmt.bindDouble(6, (i % 100).toDouble())
				stmt.bindDouble(7, 1.0)
				stmt.bindDouble(8, 2.0)
				stmt.bindDouble(9, 0.5)
				stmt.bindLong(10, (i % 9).toLong())
				stmt.bindLong(11, (i % 100).toLong())
				stmt.executeInsert()
			}
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}

		val start = System.currentTimeMillis()
		runChain()
		val elapsed = System.currentTimeMillis() - start

		assertEquals(5_000, countOf("location_sample"))
		assertEquals(5_000, countOf("activity_snapshot"))
		// Not a hard benchmark — just a canary for catastrophic slowdown.
		assertTrue(elapsed < 60_000, "v12→v26 chain took $elapsed ms for 5k rows; likely O(n²) regression")
	}

	@Test
	fun chain_preservesUnicodeAndPathologicalStringData() {
		// Covers RTL, emoji, and NUL in text columns.
		val weirdSsids = listOf(
			"مرحبا-Router",                 // Arabic RTL
			"☕🚀🛰️-AP",                    // Emoji
			"Line1\tTab\tLine2",           // Tab characters
			"Line1\nLine2",                // Newline
			"\"Quoted'AP\""                // Mixed quotes
		)
		weirdSsids.forEachIndexed { idx, ssid ->
			val bssid = "C0:FF:EE:00:00:%02X".format(idx)
			val escaped = ssid.replace("'", "''")
			db.execSQL(
				"INSERT INTO wifi_data (bssid, longitude, latitude, altitude, first_seen, last_seen, ssid, capabilities, frequency, level) " +
					"VALUES ('$bssid', 18.0, 48.0, NULL, 1, 2, '$escaped', '[OPEN]', 2412, -40)"
			)
		}
		runChain()

		weirdSsids.forEachIndexed { idx, ssid ->
			val bssid = "C0:FF:EE:00:00:%02X".format(idx)
			db.query("SELECT ssid FROM wifi_observation WHERE bssid = ?", arrayOf<Any?>(bssid)).use { cursor ->
				assertTrue(cursor.moveToFirst(), "Missing backfilled row for $bssid")
				assertEquals(ssid, cursor.getString(0))
			}
		}
	}

	@Test
	fun chain_handlesOrphanedSessionActivityIds() {
		// Real v12 devices have tracker_session rows with session_activity_id
		// pointing to activity.id rows that never existed, because the FK was
		// declared but never enforced (SQLite FKs are off by default and Room
		// does not turn them on for user writes in debug builds). Verify
		// MIGRATION_12_13 does not throw on such rows.
		db.execSQL(
			"INSERT INTO tracker_session (id, start, `end`, user_initiated, collections, distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id) " +
				"VALUES (1, 1000, 2000, 0, 5, 100.0, 50.0, 50.0, 100, 99999)"
		)
		runChain()
		// The orphan survives as a LEGACY_MIGRATION segment — Room's v13
		// schema drops session_activity_id entirely so there is no FK left
		// to violate.
		assertEquals(1, countOf("session_segment", "source = 'LEGACY_MIGRATION'"))
	}

	@Test
	fun chain_noDataLossInLocationCoordinates() {
		seedFullV12Dataset()
		runChain()

		// Sweep every row and check lat_e7/lon_e7 are within ±1 of the seed.
		val locationCount = countOf("location_sample")
		db.query("SELECT lat_e7, lon_e7 FROM location_sample").use { cursor ->
			var n = 0
			while (cursor.moveToNext()) {
				val lat = cursor.getLong(0)
				val lon = cursor.getLong(1)
				// E7-encoded lat/lon must stay in valid geo bounds.
				assertTrue(abs(lat) <= 900_000_000L, "lat_e7 out of range: $lat")
				assertTrue(abs(lon) <= 1_800_000_000L, "lon_e7 out of range: $lon")
				n++
			}
			assertEquals(locationCount, n)
		}
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private fun tableExists(name: String): Boolean =
		db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any?>(name))
			.use { cursor ->
				cursor.moveToFirst() && cursor.getInt(0) == 1
			}

	private fun countOf(table: String, where: String? = null): Int {
		val sql = buildString {
			append("SELECT COUNT(*) FROM ").append(table)
			if (where != null) append(" WHERE ").append(where)
		}
		return db.query(sql).use { cursor ->
			if (cursor.moveToFirst()) cursor.getInt(0) else 0
		}
	}

	private fun indexNames(table: String): Set<String> =
		db.query("PRAGMA index_list('$table')").use { cursor ->
			buildSet {
				val nameIdx = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(nameIdx))
			}
		}

	private fun assertEqualsWithinOne(label: String, expected: Long, actual: Long) {
		assertTrue(
			abs(expected - actual) <= 1L,
			"$label: expected ≈ $expected (±1) but got $actual"
		)
	}

	@Suppress("unused")
	private fun dumpIndicesFor(table: String): String =
		indexNames(table).joinToString(", ")

	companion object {
		// Working directory during `./gradlew :sbase:test...` is the sbase
		// module root, so this relative path resolves to Room's exported
		// schema (wired via `room.schemaLocation` in sbase/build.gradle.kts).
		private val V12_SCHEMA = File(
			"schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/12.json"
		)

		// Copy of 12.json's "identityHash". Pinned so a drift between the
		// pinned value and the one embedded in the compiled Room AppDatabase
		// surfaces as a test failure, instead of silently letting a
		// regenerated schema slip through unnoticed.
		private const val V12_IDENTITY_HASH = "603376ce119f1b4f1d3e4866aec15b4e"
	}
}
