package com.adsamcik.tracker.shared.base.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.CoordinateProvenance
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupStore
import com.adsamcik.tracker.shared.base.database.migration.MigrationBackupOpenHelperFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end migration contract for the exact databases shipped in release 2024.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Release2024_1MigrationTest {
	private lateinit var context: Application
	private val roomDatabases = mutableListOf<RoomDatabase>()
	private val openHelpers = mutableListOf<SupportSQLiteOpenHelper>()
	private val databaseNames = mutableSetOf<String>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
	}

	@After
	fun tearDown() {
		roomDatabases.forEach { runCatching { it.close() } }
		openHelpers.forEach { runCatching { it.close() } }
		databaseNames.forEach { context.deleteDatabase(it) }
		roomDatabases.clear()
		openHelpers.clear()
		databaseNames.clear()
	}

	@Test
	fun `fixtures exactly match every Room database shipped in 2024_1`() {
		fixtures.forEach { fixture ->
			val db = openFixture(fixture.assetName, fixture.version)
			db.version shouldBe fixture.version
			db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe fixture.identityHash
			}
			listTables(db) shouldBe fixture.expectedTables.toSet()
		}
	}

	@Test
	fun `actual v10 binary migrates through Room and validates the complete v40 schema`() {
		val database = migrateAppDatabase()
		val raw = database.openHelper.writableDatabase

		raw.version shouldBe CURRENT_APP_DATABASE_VERSION
		listTables(raw) shouldBe currentAppTables.toSet()
		listTables(raw).intersect(legacyDroppedTables).isEmpty() shouldBe true
	}

	@Test
	fun `accepted location samples are backfilled as migrated observations`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		val sampleCount = count(raw, "location_sample")

		count(raw, "location_observation") shouldBe sampleCount
		raw.query(
			"""
			SELECT COUNT(*)
			FROM location_observation observation
			JOIN location_sample sample
				ON sample.time_ms = observation.fix_time_ms
			WHERE observation.fix_elapsed_realtime_nanos = sample.elapsed_realtime_nanos
				AND observation.received_at_ms = sample.created_at
				AND observation.received_elapsed_realtime_nanos = sample.received_elapsed_realtime_nanos
				AND observation.delivery_age_ms IS sample.delivery_age_ms
				AND observation.lat_e7 IS sample.lat_e7
				AND observation.lon_e7 IS sample.lon_e7
				AND observation.raw_alt_m IS sample.raw_gps_alt_m
				AND observation.h_acc_m IS sample.h_acc_m
				AND observation.v_acc_m IS sample.v_acc_m
				AND observation.speed_mps IS sample.speed_mps
				AND observation.speed_accuracy_mps IS sample.speed_accuracy_mps
				AND observation.provider = sample.provider
				AND observation.acquisition_mode = sample.acquisition_mode
				AND observation.request_priority = sample.request_priority
				AND observation.permission_precision = sample.permission_precision
				AND observation.batch_index = sample.batch_index
				AND observation.batch_size = sample.batch_size
				AND observation.is_mock = sample.is_mock
				AND observation.ingress_disposition = 'MIGRATED_ACCEPTED'
				AND observation.estimator_version = sample.estimator_version
				AND observation.calibration_version = sample.calibration_version
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0) shouldBe sampleCount
		}
	}

	@Test
	fun `backup migration preserves values`() {
		val name = copyFixture("main_database.db")
		val backupDirectory = File(
			checkNotNull(context.getDatabasePath(name).parentFile),
			"release-backup",
		).apply {
			deleteRecursively()
		}
		val backupStore = DatabaseMigrationBackupStore(
			context = context,
			backupDirectory = backupDirectory,
			directorySync = {},
			scheduleExpiry = {},
			cancelExpiry = {},
		)
		val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
			.openHelperFactory(
				MigrationBackupOpenHelperFactory(
					delegate = FrameworkSQLiteOpenHelperFactory(),
					backupStore = backupStore,
					databaseName = name,
					targetVersion = CURRENT_APP_DATABASE_VERSION,
				),
			)
			.addMigrations(*releaseToCurrentMigrations)
			.allowMainThreadQueries()
			.build()
		roomDatabases += database

		val migrated = database.openHelper.writableDatabase
		migrated.version shouldBe CURRENT_APP_DATABASE_VERSION
		migrated.query(
			"SELECT legacy_lat, legacy_lon, quality FROM location_sample WHERE id = 1",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getDouble(0) shouldBe 48.1234567
			cursor.getDouble(1) shouldBe 17.9876543
			cursor.getString(2) shouldBe "HIGH"
		}

		val backup = checkNotNull(backupStore.latestBackup())
		backup.sourceVersion shouldBe RELEASE_APP_DATABASE_VERSION
		openReadOnlyDatabase(backup.file).use { source ->
			source.version shouldBe RELEASE_APP_DATABASE_VERSION
			source.rawQuery(
				"SELECT lat, lon, hor_acc, activity, confidence FROM location_data WHERE id = 1",
				null,
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getDouble(0) shouldBe 48.1234567
				cursor.getDouble(1) shouldBe 17.9876543
				cursor.getDouble(2) shouldBe 5.0
				cursor.getInt(3) shouldBe 0
				cursor.getInt(4) shouldBe 80
			}
		}
		backupDirectory.deleteRecursively()
	}

	@Test
	fun `all location rows retain ids values and activity snapshots`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		count(raw, "location_sample") shouldBe 6
		count(raw, "activity_snapshot") shouldBe 6

		val expected = listOf(
			LocationExpected(1, 1_000, 481_234_567, 179_876_543, 48.1234567, 17.9876543, 200.5, 5.0, 1.0, 2.5, 0.5, "HIGH", "MOVING", 0, 80),
			LocationExpected(2, 2_000, -899_999_999, 1_799_999_999, -89.9999999, 179.9999999, null, null, null, null, null, "COARSE", "UNKNOWN", 4, 0),
			LocationExpected(3, 3_000, 899_999_999, -1_799_999_999, 89.9999999, -179.9999999, -430.25, 49.999, 999.0, 0.0, 0.0, "MEDIUM", "MOVING", 7, 100),
			LocationExpected(4, 4_000, 1, -1, 0.0000001, -0.0000001, 8_848.86, 50.0, 2.0, 123.45, 9.9, "LOW", "MOVING", 8, 55),
			LocationExpected(5, 5_000, 0, 0, 0.0, 0.0, 0.0, 9.999, 0.0, -1.0, 0.0, "HIGH", "STILL", 3, 1),
			LocationExpected(6, 6_000, 2, -2, 0.00000016, -0.00000016, 12.3456789, 10.0, 3.0, 1.0, 0.1, "MEDIUM", "MOVING", 1, 50),
		)

		raw.query(
			"""
			SELECT l.id, l.time_ms, l.lat_e7, l.lon_e7, l.legacy_lat, l.legacy_lon,
				l.alt_m, l.raw_gps_alt_m, l.legacy_alt_m, l.h_acc_m, l.v_acc_m,
				l.speed_mps, l.speed_accuracy_mps, l.quality, l.motion_state,
				a.activity_type, a.confidence
			FROM location_sample l
			JOIN activity_snapshot a ON a.id = l.id
			ORDER BY l.id
			""".trimIndent(),
		).use { cursor ->
			expected.forEach { item ->
				cursor.moveToNext() shouldBe true
				cursor.getLong(0) shouldBe item.id
				cursor.getLong(1) shouldBe item.timeMs
				cursor.getInt(2) shouldBe item.latE7
				cursor.getInt(3) shouldBe item.lonE7
				cursor.getDouble(4) shouldBe item.legacyLat
				cursor.getDouble(5) shouldBe item.legacyLon
				cursor.nullableDouble(6) shouldBe item.altM
				cursor.nullableDouble(7) shouldBe item.altM
				cursor.nullableDouble(8) shouldBe item.altM
				cursor.nullableDouble(9) shouldBe item.hAccM
				cursor.nullableDouble(10) shouldBe item.vAccM
				cursor.nullableDouble(11) shouldBe item.speedMps
				cursor.nullableDouble(12) shouldBe item.speedAccuracyMps
				cursor.getString(13) shouldBe item.quality
				cursor.getString(14) shouldBe item.motionState
				cursor.getInt(15) shouldBe item.activity
				cursor.getInt(16) shouldBe item.confidence
			}
			cursor.moveToNext() shouldBe false
		}
	}

	@Test
	fun `every valid session preserves every legacy field and original activity id`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		val expectedActivityIds = (rangeOfNativeActivityIds + listOf(7, null))

		count(raw, "session_segment") shouldBe expectedActivityIds.size
		raw.query(
			"""
			SELECT id, start_time_ms, end_time_ms, distance_m, steps, sample_count,
				legacy_user_initiated, legacy_distance_on_foot_m,
				legacy_distance_in_vehicle_m, legacy_activity_id, source
			FROM session_segment
			ORDER BY id
			""".trimIndent(),
		).use { cursor ->
			expectedActivityIds.forEachIndexed { index, activityId ->
				val id = index + 1
				cursor.moveToNext() shouldBe true
				cursor.getInt(0) shouldBe id
				cursor.getLong(1) shouldBe 10_000L * id
				cursor.getLong(2) shouldBe 10_000L * id + 5_000L
				cursor.getDouble(3) shouldBe id * 100.25
				cursor.getInt(4) shouldBe id * 10
				cursor.getInt(5) shouldBe id + 1
				cursor.getInt(6) shouldBe id % 2
				cursor.getDouble(7) shouldBe id * 60.5
				cursor.getDouble(8) shouldBe id * 39.75
				cursor.nullableLong(9) shouldBe activityId?.toLong()
				cursor.getString(10) shouldBe "LEGACY_MIGRATION"
			}
			cursor.moveToNext() shouldBe false
		}
	}

	@Test
	fun `invalid legacy sessions are quarantined byte-for-byte instead of dropped`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		count(raw, "legacy_rejected_tracker_session") shouldBe 2

		raw.query(
			"""
			SELECT id, start, `end`, user_initiated, collections, distance,
				distance_on_foot, distance_in_vehicle, steps, session_activity_id
			FROM legacy_rejected_tracker_session
			ORDER BY id
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 100L
			cursor.getLong(1) shouldBe 900_000L
			cursor.getLong(2) shouldBe 900_000L
			cursor.getInt(3) shouldBe 1
			cursor.getInt(4) shouldBe 10
			cursor.getDouble(5) shouldBe 1.0
			cursor.getDouble(6) shouldBe 1.0
			cursor.getDouble(7) shouldBe 0.0
			cursor.getInt(8) shouldBe 1
			cursor.getLong(9) shouldBe -2L

			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 101L
			cursor.getLong(1) shouldBe 910_000L
			cursor.getLong(2) shouldBe 920_000L
			cursor.getInt(3) shouldBe 0
			cursor.getInt(4) shouldBe 1
			cursor.getDouble(5) shouldBe 2.0
			cursor.getDouble(6) shouldBe 0.0
			cursor.getDouble(7) shouldBe 2.0
			cursor.getInt(8) shouldBe 0
			cursor.getLong(9) shouldBe -5L
			cursor.moveToNext() shouldBe false
		}
	}

	@Test
	fun `wifi cell and aggregate wifi count fields survive without truncation`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		count(raw, "wifi_observation") shouldBe 2
		count(raw, "cell_sample") shouldBe 3
		count(raw, "legacy_location_wifi_count") shouldBe 2

		raw.query(
			"""
			SELECT bssid, ssid, capabilities, frequency, level, lat_e7, lon_e7,
				legacy_first_seen_ms, time_ms, legacy_alt_m, provenance, legacy_lat, legacy_lon
			FROM wifi_observation ORDER BY bssid
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToNext() shouldBe true
			cursor.getString(0) shouldBe "AA:BB:CC:DD:EE:01"
			cursor.getString(1) shouldBe "Home 'quoted'"
			cursor.getString(2) shouldBe "[WPA2-PSK-CCMP][ESS]"
			cursor.getInt(3) shouldBe 2412
			cursor.getInt(4) shouldBe -55
			cursor.getInt(5) shouldBe 481_250_000
			cursor.getInt(6) shouldBe 178_750_000
			cursor.getLong(7) shouldBe 1_700_000_000_000L
			cursor.getLong(8) shouldBe 1_700_000_050_000L
			cursor.getDouble(9) shouldBe 250.75
			cursor.getString(10) shouldBe CoordinateProvenance.LEGACY_MIGRATION.name
			cursor.getDouble(11) shouldBe 48.125
			cursor.getDouble(12) shouldBe 17.875

			cursor.moveToNext() shouldBe true
			cursor.getString(0) shouldBe "AA:BB:CC:DD:EE:02"
			cursor.getString(1) shouldBe "Síť-测试"
			cursor.getString(2) shouldBe ""
			cursor.getInt(3) shouldBe 5955
			cursor.getInt(4) shouldBe -127
			cursor.isNull(5) shouldBe true
			cursor.isNull(6) shouldBe true
			cursor.getLong(7) shouldBe 1_700_000_100_000L
			cursor.getLong(8) shouldBe 1_700_000_200_000L
			cursor.isNull(9) shouldBe true
			cursor.getString(10) shouldBe CoordinateProvenance.LEGACY_MIGRATION.name
			cursor.isNull(11) shouldBe true
			cursor.isNull(12) shouldBe true
		}

		raw.query(
			"""
			SELECT id, time_ms, cell_id, mcc, mnc, network_type, signal_strength,
				lat_e7, lon_e7, legacy_alt_m, provenance, legacy_mcc, legacy_mnc,
				legacy_source_id, legacy_lat, legacy_lon
			FROM cell_sample ORDER BY id
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 1L
			cursor.getLong(1) shouldBe 1_700_000_000_000L
			cursor.getLong(2) shouldBe 9_999L
			cursor.getInt(3) shouldBe 230
			cursor.getInt(4) shouldBe 1
			cursor.getInt(5) shouldBe 13
			cursor.getInt(6) shouldBe 40
			cursor.getInt(7) shouldBe 481_250_000
			cursor.getInt(8) shouldBe 178_750_000
			cursor.getDouble(9) shouldBe 250.25
			cursor.getString(10) shouldBe CoordinateProvenance.LEGACY_MIGRATION.name
			cursor.getString(11) shouldBe "230"
			cursor.getString(12) shouldBe "01"
			cursor.getLong(13) shouldBe 1L
			cursor.getDouble(14) shouldBe 48.125
			cursor.getDouble(15) shouldBe 17.875

			cursor.moveToNext() shouldBe true
			cursor.getLong(2) shouldBe 68_719_476_735L
			cursor.getInt(5) shouldBe 20
			cursor.getInt(6) shouldBe 97
			cursor.getInt(7) shouldBe -899_999_999
			cursor.getInt(8) shouldBe 1_799_999_999
			cursor.isNull(9) shouldBe true
			cursor.getString(11) shouldBe "310"
			cursor.getString(12) shouldBe "260"
			cursor.getLong(13) shouldBe 2L
			cursor.getDouble(14) shouldBe -89.9999999
			cursor.getDouble(15) shouldBe 179.9999999

			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 3L
			cursor.getLong(2) shouldBe 9_999L
			cursor.getString(11) shouldBe "001"
			cursor.getString(12) shouldBe "01"
			cursor.getLong(13) shouldBe 3L
			cursor.moveToNext() shouldBe false
		}

		raw.query(
			"SELECT id, time, count, lat, lon, alt FROM legacy_location_wifi_count ORDER BY id",
		).use { cursor ->
			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 1L
			cursor.getLong(1) shouldBe 1_700_000_000_000L
			cursor.getInt(2) shouldBe 3
			cursor.getDouble(3) shouldBe 48.125
			cursor.getDouble(4) shouldBe 17.875
			cursor.getDouble(5) shouldBe 200.0
			cursor.moveToNext() shouldBe true
			cursor.getLong(0) shouldBe 2L
			cursor.getInt(2) shouldBe 0
			cursor.isNull(5) shouldBe true
		}
	}

	@Test
	fun `migrated provenance is readable through generated Room DAOs`() = runTest {
		val database = migrateAppDatabase()
		val wifi = database.wifiObservationDao()
			.getForBssid("AA:BB:CC:DD:EE:01", Long.MIN_VALUE, Long.MAX_VALUE)
			.single()
		val cells = database.cellSampleDao()
			.getAllBetweenFlow(Long.MIN_VALUE, Long.MAX_VALUE)
			.first()

		wifi.provenance shouldBe CoordinateProvenance.LEGACY_MIGRATION
		wifi.legacyFirstSeenMs shouldBe 1_700_000_000_000L
		wifi.legacyAltM shouldBe 250.75
		wifi.legacyLat shouldBe 48.125
		wifi.legacyLon shouldBe 17.875
		cells.map { it.provenance }.distinct() shouldBe listOf(CoordinateProvenance.LEGACY_MIGRATION)
		cells.first().legacyAltM shouldBe 250.25
		cells.first().legacySourceId shouldBe 1L
		cells.first().legacyLat shouldBe 48.125
		cells.first().legacyLon shouldBe 17.875
	}

	@Test
	fun `migrated motion and segment enums materialize through generated Room DAOs`() = runTest {
		val database = migrateAppDatabase()
		val locations = database.locationSampleDao()
			.getAllBetweenFlow(Long.MIN_VALUE, Long.MAX_VALUE)
			.first()
		val segments = database.sessionSegmentDao()
			.getAllBetween(Long.MIN_VALUE, Long.MAX_VALUE)

		locations.map { it.motionState } shouldBe listOf(
			MotionState.MOVING,
			MotionState.UNKNOWN,
			MotionState.MOVING,
			MotionState.MOVING,
			MotionState.STILL,
			MotionState.MOVING,
		)
		locations.map { it.quality } shouldBe listOf(
			SampleQuality.HIGH,
			SampleQuality.COARSE,
			SampleQuality.MEDIUM,
			SampleQuality.LOW,
			SampleQuality.HIGH,
			SampleQuality.MEDIUM,
		)
		segments.map { it.source }.distinct() shouldBe listOf(SegmentSource.LEGACY_MIGRATION)
	}

	@Test
	fun `migrated native activities are visible through typed segment statistics`() = runTest {
		val dao = migrateAppDatabase().sessionSegmentDao()

		dao.countByActivity(7) shouldBe 2L
		dao.countByActivity(8) shouldBe 1L
		dao.countByActivity(1) shouldBe 1L
		dao.countByActivity(0) shouldBe 26L
		dao.countByActivity(-22) shouldBe 3L
		dao.countDistinctActivities() shouldBe 5L
	}

	@Test
	fun `reference data and custom activities survive unchanged`() {
		val raw = migrateAppDatabase().openHelper.writableDatabase
		count(raw, "activity") shouldBe 34
		count(raw, "network_operator") shouldBe 3

		raw.query("SELECT name, iconName FROM activity WHERE id = 7").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "Custom run"
			cursor.getString(1) shouldBe "custom_run"
		}
		raw.query("SELECT name FROM network_operator WHERE mcc = '001' AND mnc = '01'").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "Leading Zero"
		}
	}

	@Test
	fun `unchanged preference database opens through current Room without migration`() {
		val name = copyFixture("preference_database.db")
		val database = Room.databaseBuilder(context, PreferenceDatabase::class.java, name)
			.allowMainThreadQueries()
			.build()
		roomDatabases += database

		val raw = database.openHelper.writableDatabase
		raw.version shouldBe 1
		count(raw, "generic") shouldBe 3
		count(raw, "notification") shouldBe 3
		raw.query("SELECT value FROM generic WHERE id = 'json'").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldContain "Síť-测试"
		}
	}

	@Test
	fun `retired databases remain intact`() {
		val challenge = openFixture("challenge_database.db", 1)
		count(challenge, "entry") shouldBe 3
		count(challenge, "challenge_session_data") shouldBe 3
		count(challenge, "challenge_explorer") shouldBe 1
		count(challenge, "challenge_walk_distance") shouldBe 1
		count(challenge, "challenge_step") shouldBe 1

		val stats = openFixture("stats_database.db", 1)
		count(stats, "statCache") shouldBe 3
		stats.query("SELECT value FROM statCache WHERE session_id = 101").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldContain "Síť-测试"
		}
	}

	@Test
	fun `delete all clears legacy archives and advances evidence lifecycle`() = runTest {
		val database = migrateAppDatabase()
		val raw = database.openHelper.writableDatabase
		count(raw, "legacy_rejected_tracker_session") shouldBe 2
		count(raw, "legacy_location_wifi_count") shouldBe 2
		raw.execSQL(
			"""
			INSERT INTO domain_event(event_type, processor_id, timestamp_ms, payload)
			VALUES ('CellDiscovered', 'test', 1000, '{"lat":48.1,"lon":17.8}')
			""".trimIndent(),
		)
		raw.execSQL(
			"""
			INSERT INTO domain_event_cursor(consumer_id, last_processed_ms, last_processed_id)
			VALUES ('test', 1000, 1)
			""".trimIndent(),
		)

		AppDatabase.deleteAllCollectedData(
			database = database,
			collectedDataEpoch = 7L,
			retainedFromMs = 2_000L,
			updatedAtMs = 3_000L,
		)

		count(raw, "location_sample") shouldBe 0
		count(raw, "location_observation") shouldBe 0
		count(raw, "session_segment") shouldBe 0
		count(raw, "legacy_rejected_tracker_session") shouldBe 0
		count(raw, "legacy_location_wifi_count") shouldBe 0
		count(raw, "domain_event") shouldBe 0
		count(raw, "domain_event_cursor") shouldBe 0
		val sourceEvidenceState = database.sourceEvidenceStateDao().get()
		sourceEvidenceState?.revision shouldBe 1L
		sourceEvidenceState?.collectedDataEpoch shouldBe 7L
		sourceEvidenceState?.retainedFromMs shouldBe 2_000L
		sourceEvidenceState?.updatedAtMs shouldBe 3_000L
	}

	@Test
	fun `every challenge and stats session reference remains resolvable after migration`() {
		val app = migrateAppDatabase().openHelper.writableDatabase
		val challenge = openFixture("challenge_database.db", 1)
		val stats = openFixture("stats_database.db", 1)
		val referencedIds = buildSet {
			challenge.query("SELECT id FROM challenge_session_data").use { cursor ->
				while (cursor.moveToNext()) add(cursor.getLong(0))
			}
			stats.query("SELECT DISTINCT session_id FROM statCache").use { cursor ->
				while (cursor.moveToNext()) add(cursor.getLong(0))
			}
		}

		referencedIds.forEach { sessionId ->
			app.query(
				"""
				SELECT
					(SELECT COUNT(*) FROM session_segment WHERE id = ?) +
					(SELECT COUNT(*) FROM legacy_rejected_tracker_session WHERE id = ?)
				""".trimIndent(),
				arrayOf(sessionId, sessionId),
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getInt(0) shouldBe 1
			}
		}
	}

	private fun migrateAppDatabase(): AppDatabase {
		val name = copyFixture("main_database.db")
		val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
			.addMigrations(*releaseToCurrentMigrations)
			.allowMainThreadQueries()
			.build()
		roomDatabases += database
		database.openHelper.writableDatabase
		return database
	}

	private fun openFixture(
		assetName: String,
		targetVersion: Int,
		onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, old, new ->
			error("Unexpected upgrade for $assetName: $old->$new")
		},
	): SupportSQLiteDatabase {
		val name = copyFixture(assetName)
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(name)
				.callback(object : SupportSQLiteOpenHelper.Callback(targetVersion) {
					override fun onCreate(db: SupportSQLiteDatabase) =
						error("Fixture was not copied for $assetName")

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) = onUpgrade(db, oldVersion, newVersion)
				})
				.build(),
		)
		openHelpers += helper
		return helper.writableDatabase
	}

	private fun copyFixture(assetName: String): String {
		val name = "${assetName.removeSuffix(".db")}_${databaseNames.size}.db"
		context.deleteDatabase(name)
		databaseNames += name
		val target = context.getDatabasePath(name)
		target.parentFile?.mkdirs()
		val resource = "baseline/2024.1/$assetName"
		checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
			"Missing release fixture: $resource"
		}.use { input ->
			target.outputStream().use(input::copyTo)
		}
		return name
	}

	private fun openReadOnlyDatabase(file: File): android.database.sqlite.SQLiteDatabase =
		android.database.sqlite.SQLiteDatabase.openDatabase(
			file.path,
			null,
			android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
		)

	private fun listTables(db: SupportSQLiteDatabase): Set<String> =
		db.query(
			"""
			SELECT name FROM sqlite_master
			WHERE type = 'table'
				AND name NOT LIKE 'sqlite_%'
				AND name NOT LIKE 'android_%'
				AND name != 'room_master_table'
			""".trimIndent(),
		).use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(0))
			}
		}

	private fun count(db: SupportSQLiteDatabase, table: String): Int =
		db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
			cursor.moveToFirst()
			cursor.getInt(0)
		}

	private fun android.database.Cursor.nullableDouble(index: Int): Double? =
		if (isNull(index)) null else getDouble(index)

	private fun android.database.Cursor.nullableLong(index: Int): Long? =
		if (isNull(index)) null else getLong(index)

	private data class LocationExpected(
		val id: Long,
		val timeMs: Long,
		val latE7: Int,
		val lonE7: Int,
		val legacyLat: Double,
		val legacyLon: Double,
		val altM: Double?,
		val hAccM: Double?,
		val vAccM: Double?,
		val speedMps: Double?,
		val speedAccuracyMps: Double?,
		val quality: String,
		val motionState: String,
		val activity: Int,
		val confidence: Int,
	)

	private data class Fixture(
		val assetName: String,
		val version: Int,
		val identityHash: String,
		val expectedTables: List<String>,
	)

	private companion object {
		const val RELEASE_APP_DATABASE_VERSION = 10
		const val CURRENT_APP_DATABASE_VERSION = 40

		val rangeOfNativeActivityIds = (-34..-2).toList()

		val releaseToCurrentMigrations: Array<Migration> =
			AppDatabase.migrations.filter { it.startVersion >= 10 }.toTypedArray()

		val legacyDroppedTables = setOf(
			"location_data",
			"tracker_session",
			"wifi_data",
			"cell_location",
			"location_wifi_count",
		)

		val currentAppTables = listOf(
			"activity",
			"network_operator",
			"location_sample",
			"location_observation",
			"location_observation_decision",
			"step_interval",
			"activity_snapshot",
			"cell_sample",
			"wifi_observation",
			"tracker_run",
			"tracker_state_event",
			"source_evidence_state",
			"session_segment",
			"legacy_rejected_tracker_session",
			"legacy_location_wifi_count",
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
			"import_job_receipt",
			"import_entry_receipt",
			"storage_size_snapshot",
			"domain_event",
			"domain_event_cursor",
			"pressure_sample",
			"ski_run_segment",
			"pending_signal",
			"quarantined_signal",
			"xp_ledger",
			"player_profile",
			"minigame_score",
			"osm_import",
			"osm_way",
			"osm_way_cell",
			"trajectory_reconstruction_run",
			"trajectory_state",
			"trajectory_source_link",
			"route_hypothesis",
			"visit_interval",
			"import_job_receipt",
			"import_entry_receipt",
		)

		val fixtures = listOf(
			Fixture(
				"main_database.db",
				10,
				"0450ddcfb62c0bb907dbd58ebb962e11",
				legacyDroppedTables.toList() + listOf("activity", "network_operator"),
			),
			Fixture(
				"preference_database.db",
				1,
				"0608179a3962e9cd5340a093ba0378d5",
				listOf("generic", "notification"),
			),
			Fixture(
				"points_database.db",
				1,
				"67361523b8053dd6727d713508ad45ab",
				listOf("points_awarded"),
			),
			Fixture(
				"stats_database.db",
				1,
				"9bcc267288495935f80c47544aaf0bbf",
				listOf("statCache"),
			),
			Fixture(
				"challenge_database.db",
				1,
				"a00ec9e9199c6d7d4cf080768b809b93",
				listOf(
					"challenge_session_data",
					"entry",
					"challenge_explorer",
					"challenge_walk_distance",
					"challenge_step",
				),
			),
		)
	}
}
