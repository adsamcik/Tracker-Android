package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.legacy.ACTIVE_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseException
import com.adsamcik.tracker.shared.base.database.legacy.LegacyDatabaseRepository
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportRoomCallback
import com.adsamcik.tracker.shared.base.database.legacy.LegacyImportStatus
import com.adsamcik.tracker.shared.base.database.legacy.LegacyV26DatabaseNormalizer
import com.adsamcik.tracker.shared.base.database.legacy.hasCompletedLegacyImport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end coverage for the public v26 vault -> fresh current-schema import boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyV26ImportTest {
	private lateinit var context: Application
	private val roomDatabases = mutableListOf<RoomDatabase>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		deleteTestDatabases()
		context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}

	@After
	fun tearDown() {
		roomDatabases.forEach { runCatching { it.close() } }
		roomDatabases.clear()
		deleteTestDatabases()
		context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
			.edit()
			.clear()
			.commit()
	}

	@Test
	fun `clean install creates only the active database`() {
		val raw = openActive()

		raw.version shouldBe CURRENT_DATABASE_VERSION
		context.getDatabasePath(ACTIVE_DATABASE_NAME).exists() shouldBe true
		context.getDatabasePath(LEGACY_DATABASE_NAME).exists() shouldBe false
		LegacyDatabaseRepository(context).currentState().database shouldBe null
	}

	@Test
	fun `released v26 data imports into a fresh schema with canonical evidence identities`() {
		prepareReleasedV26(::seedV26EdgeRows)

		val raw = openActive()

		raw.version shouldBe CURRENT_DATABASE_VERSION
		hasCompletedLegacyImport(raw) shouldBe true
		count(raw, "location_sample") shouldBe 1L
		count(raw, "location_observation") shouldBe 1L
		count(raw, "daily_summary") shouldBe 1L
		count(raw, "achievement_progress") shouldBe 0L
		longValue(raw, "SELECT primary_activity FROM session_segment WHERE id = 9007") shouldBe 7L

		raw.query(
			"""
			SELECT time_ms, lat_e7, lon_e7, alt_m, received_elapsed_realtime_nanos,
				acquisition_mode, request_priority, permission_precision,
				batch_index, batch_size, is_mock, estimator_version, calibration_version,
				source_signal_id
			FROM location_sample WHERE id = 9001
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 1_700_100_000_000L
			cursor.getInt(1) shouldBe 501_000_000
			cursor.getInt(2) shouldBe 142_000_000
			cursor.getDouble(3) shouldBe 321.25
			cursor.getLong(4) shouldBe 0L
			cursor.getString(5) shouldBe "UNKNOWN"
			cursor.getString(6) shouldBe "UNKNOWN"
			cursor.getString(7) shouldBe "UNKNOWN"
			cursor.getInt(8) shouldBe 0
			cursor.getInt(9) shouldBe 1
			cursor.getInt(10) shouldBe 0
			cursor.getInt(11) shouldBe 1
			cursor.getInt(12) shouldBe 0
			cursor.getString(13) shouldBe "legacy:location_sample:9001"
		}

		raw.query(
			"""
			SELECT raw_alt_m, ingress_disposition, source_signal_id, source_event_id,
				callback_id, clock_domain_id, source_revision
			FROM location_observation WHERE fix_time_ms = 1700100000000
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.isNull(0) shouldBe true
			cursor.getString(1) shouldBe "MIGRATED_ACCEPTED"
			cursor.getString(2).startsWith("legacy:location_observation:") shouldBe true
			cursor.getString(3).startsWith("legacy:location_observation_event:") shouldBe true
			cursor.getString(4).startsWith("legacy:location_observation_callback:") shouldBe true
			cursor.getString(5) shouldBe "legacy:unknown"
			cursor.getLong(6) shouldBe 0L
			cursor.moveToNext() shouldBe false
		}

		sourceIdentity(raw, "step_interval", 9002L) shouldBe "legacy:step_interval:9002"
		sourceIdentity(raw, "activity_snapshot", 9003L) shouldBe
			"legacy:activity_snapshot:9003"
		sourceIdentity(raw, "pressure_sample", 9004L) shouldBe "legacy:pressure_sample:9004"
		sourceIdentity(raw, "cell_sample", 9005L) shouldBe "legacy:cell_sample:9005"
		sourceIdentity(raw, "wifi_observation", 9006L) shouldBe
			"legacy:wifi_observation:9006"
		sourceItemIndex(raw, "cell_sample", 9005L) shouldBe 0
		sourceItemIndex(raw, "wifi_observation", 9006L) shouldBe 0
		coordinates(raw, "cell_sample", 9005L) shouldBe (500_000_000 to 140_000_000)
		coordinates(raw, "wifi_observation", 9006L) shouldBe (499_000_000 to 139_000_000)

		val nextLocationId = raw.compileStatement(
			"""
			INSERT INTO location_sample(
				time_ms, elapsed_realtime_nanos, provider, quality, created_at, source_signal_id
			) VALUES (1700100001000, 123456790, 'gps', 'HIGH', 1700100001100, 'current:1')
			""".trimIndent(),
		).executeInsert()
		(nextLocationId > 9001L) shouldBe true

		val state = LegacyDatabaseRepository(context).currentState()
		state.importStatus shouldBe LegacyImportStatus.RUNNING
		state.report?.importedRows?.get("location_sample") shouldBe 1L
		state.report?.importedRows?.get("location_observation") shouldBe 1L
		state.report?.skippedRows?.get("achievement_progress") shouldBe 1L

		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { source ->
			source.version shouldBe RELEASED_DATABASE_VERSION
			androidCount(source, "location_sample") shouldBe 1L
			androidCount(source, "achievement_progress") shouldBe 1L
		}
	}

	@Test
	fun `exact released v26 schema imports without unreleased fallback columns`() {
		prepareReleasedV26 { database ->
			seedV26EdgeRows(database)
			database.execSQL(
				"UPDATE location_sample SET lat_e7 = 501000000, lon_e7 = 142000000, " +
					"alt_m = 321.25",
			)
			database.execSQL("UPDATE cell_sample SET lat_e7 = 500000000, lon_e7 = 140000000")
			database.execSQL(
				"UPDATE wifi_observation SET lat_e7 = 499000000, lon_e7 = 139000000",
			)
			database.execSQL("UPDATE session_segment SET primary_activity = 7")
			rebuildAsReleasedV26(database)
		}

		val active = openActive()

		hasCompletedLegacyImport(active) shouldBe true
		count(active, "location_sample") shouldBe 1L
		coordinates(active, "location_sample", 9001L) shouldBe (501_000_000 to 142_000_000)
		coordinates(active, "cell_sample", 9005L) shouldBe (500_000_000 to 140_000_000)
		coordinates(active, "wifi_observation", 9006L) shouldBe (499_000_000 to 139_000_000)
		longValue(
			active,
			"SELECT primary_activity FROM session_segment WHERE id = 9007",
		) shouldBe 7L
	}

	@Test
	fun `compatible pre-release v34 imports the released data subset without mutating source`() {
		prepareReleasedV26 { database ->
			seedV26EdgeRows(database)
			database.execSQL("CREATE TABLE unreleased_v34_metadata(id INTEGER PRIMARY KEY)")
			database.version = 34
		}

		val active = openActive()

		hasCompletedLegacyImport(active) shouldBe true
		count(active, "location_sample") shouldBe 1L
		count(active, "location_observation") shouldBe 1L
		LegacyDatabaseRepository(context).currentState().report?.sourceVersion shouldBe 34
		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { source ->
			source.version shouldBe 34
			androidCount(source, "location_sample") shouldBe 1L
			androidCount(source, "unreleased_v34_metadata") shouldBe 0L
		}
	}

	@Test
	fun `later incompatible development schema remains rejected`() {
		prepareReleasedV26 { database -> database.version = 35 }

		shouldThrow<LegacyDatabaseException> { openActive() }
		LegacyDatabaseRepository(context).currentState().importStatus shouldBe
			LegacyImportStatus.FAILED
	}

	@Test
	fun `older public database is normalized on a disposable copy and source stays unchanged`() {
		copyFixtureToLegacy()

		val raw = openActive()

		hasCompletedLegacyImport(raw) shouldBe true
		count(raw, "location_sample") shouldBe 6L
		count(raw, "location_observation") shouldBe 6L
		count(raw, "session_segment") shouldBe 35L
		LegacyDatabaseRepository(context).currentState().report?.sourceVersion shouldBe
			FIXTURE_DATABASE_VERSION
		context.getDatabasePath(STAGING_DATABASE_NAME).exists() shouldBe false

		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { source ->
			source.version shouldBe FIXTURE_DATABASE_VERSION
			androidCount(source, "location_data") shouldBe 6L
		}
	}

	@Test
	fun `failed Room onCreate retries on the same database helper`() {
		prepareReleasedV26(::seedV26EdgeRows)
		editLegacy { database ->
			database.execSQL("ALTER TABLE daily_summary RENAME TO daily_summary_temporarily_missing")
		}
		val room = buildActive()

		runCatching { room.openHelper.writableDatabase }.isFailure shouldBe true
		LegacyDatabaseRepository(context).currentState().importStatus shouldBe LegacyImportStatus.FAILED

		editLegacy { database ->
			database.execSQL("ALTER TABLE daily_summary_temporarily_missing RENAME TO daily_summary")
		}
		val raw = room.openHelper.writableDatabase

		hasCompletedLegacyImport(raw) shouldBe true
		count(raw, "location_sample") shouldBe 1L
		count(raw, "location_observation") shouldBe 1L
		LegacyDatabaseRepository(context).currentState().importStatus shouldBe LegacyImportStatus.RUNNING
	}

	@Test
	fun `older source fails before staging copy when free space is insufficient`() {
		copyFixtureToLegacy()
		val normalizer = LegacyV26DatabaseNormalizer(
			context = context,
			repository = LegacyDatabaseRepository(context),
			publicMigrations = AppDatabase.legacyPublicMigrationsThroughV26,
			openHelperFactory = FrameworkSQLiteOpenHelperFactory(),
			usableSpaceBytes = { 0L },
		)

		shouldThrow<LegacyDatabaseException> { normalizer.prepare() }
		context.getDatabasePath(STAGING_DATABASE_NAME).exists() shouldBe false
		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { source -> source.version shouldBe FIXTURE_DATABASE_VERSION }
	}

	@Test
	fun `downgraded v26 writer remains isolated from the active v27 database`() {
		prepareReleasedV26(::seedV26EdgeRows)
		val active = openActive()

		editLegacy { legacy ->
			legacy.version shouldBe RELEASED_DATABASE_VERSION
			legacy.execSQL(
				"UPDATE location_sample SET provider = 'downgraded-v26' WHERE id = 9001",
			)
			androidStringValue(
				legacy,
				"SELECT provider FROM location_sample WHERE id = 9001",
			) shouldBe "downgraded-v26"
		}

		active.version shouldBe CURRENT_DATABASE_VERSION
		stringValue(active, "SELECT provider FROM location_sample WHERE id = 9001") shouldBe "gps"
		active.execSQL("UPDATE location_sample SET provider = 'active-v27' WHERE id = 9001")

		editLegacy { legacy ->
			androidStringValue(
				legacy,
				"SELECT provider FROM location_sample WHERE id = 9001",
			) shouldBe "downgraded-v26"
		}
	}

	@Test
	fun `large v26 location history imports atomically within a practical bound`() {
		// This creates 25,000 streamed source rows and 50,000 target evidence rows. The wide
		// limit catches accidental quadratic/materializing behavior without acting as a benchmark.
		prepareReleasedV26 { database ->
			seedLargeLocationHistory(database, LARGE_LOCATION_ROW_COUNT)
		}

		val startedAtNanos = System.nanoTime()
		val active = openActive()
		val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L

		count(active, "location_sample") shouldBe LARGE_LOCATION_ROW_COUNT.toLong()
		count(active, "location_observation") shouldBe LARGE_LOCATION_ROW_COUNT.toLong()
		sourceIdentity(
			active,
			"location_sample",
			LARGE_LOCATION_FIRST_ID + LARGE_LOCATION_ROW_COUNT - 1L,
		) shouldBe "legacy:location_sample:${LARGE_LOCATION_FIRST_ID + LARGE_LOCATION_ROW_COUNT - 1L}"
		LegacyDatabaseRepository(context).currentState().report
			?.importedRows
			?.get("location_sample") shouldBe LARGE_LOCATION_ROW_COUNT.toLong()
		(elapsedMillis < LARGE_IMPORT_MAX_MILLIS) shouldBe true

		editLegacy { source ->
			androidCount(source, "location_sample") shouldBe LARGE_LOCATION_ROW_COUNT.toLong()
		}
	}

	@Test
	fun `completed import can export delete and restart with active data intact`() {
		prepareReleasedV26(::seedV26EdgeRows)
		val active = openActive()
		val repository = LegacyDatabaseRepository(context, nowMillis = { 1234L })
		repository.markComplete()

		val currentLocationId = active.compileStatement(
			"""
			INSERT INTO location_sample(
				time_ms, elapsed_realtime_nanos, provider, quality, created_at, source_signal_id
			) VALUES (1700100002000, 123456791, 'gps', 'HIGH', 1700100002100,
				'current:manual-flow')
			""".trimIndent(),
		).executeInsert()
		count(active, "location_sample") shouldBe 2L

		val exported = ByteArrayOutputStream()
		repository.export(exported)
		val exportedFile = File(context.cacheDir, "legacy-v26-export-test.db")
		if (exportedFile.exists()) exportedFile.delete() shouldBe true
		try {
			exportedFile.writeBytes(exported.toByteArray())
			SQLiteDatabase.openDatabase(
				exportedFile.path,
				null,
				SQLiteDatabase.OPEN_READONLY,
			).use { portable ->
				portable.version shouldBe RELEASED_DATABASE_VERSION
				androidCount(portable, "location_sample") shouldBe 1L
				androidStringValue(
					portable,
					"SELECT provider FROM location_sample WHERE id = 9001",
				) shouldBe "gps"
			}
		} finally {
			if (exportedFile.exists()) exportedFile.delete() shouldBe true
		}

		(repository.delete() > 0L) shouldBe true
		context.getDatabasePath(LEGACY_DATABASE_NAME).exists() shouldBe false
		context.getDatabasePath(ACTIVE_DATABASE_NAME).exists() shouldBe true

		roomDatabases.forEach(RoomDatabase::close)
		roomDatabases.clear()
		val restartedState = LegacyDatabaseRepository(context).currentState()
		restartedState.database shouldBe null
		restartedState.importStatus shouldBe LegacyImportStatus.NOT_STARTED
		restartedState.report shouldBe null
		restartedState.externallyExported shouldBe false

		val reopened = openActive()
		reopened.version shouldBe CURRENT_DATABASE_VERSION
		hasCompletedLegacyImport(reopened) shouldBe true
		count(reopened, "location_sample") shouldBe 2L
		sourceIdentity(reopened, "location_sample", currentLocationId) shouldBe
			"current:manual-flow"
	}

	private fun seedV26EdgeRows(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO location_sample(
				id, time_ms, elapsed_realtime_nanos, lat_e7, lon_e7,
				alt_m, raw_gps_alt_m, h_acc_m, v_acc_m, speed_mps,
				speed_accuracy_mps, provider, quality, motion_state, policy,
				bucket_id, created_at, legacy_lat, legacy_lon, legacy_alt_m
			) VALUES (
				9001, 1700100000000, 123456789, NULL, NULL,
				NULL, NULL, NULL, 4.5, NULL,
				NULL, 'gps', 'HIGH', NULL, NULL,
				NULL, 1700100000100, 50.1, 14.2, 321.25
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO step_interval(
				id, start_time_ms, end_time_ms, step_count, sensor_value_start,
				sensor_value_end, sensor_reset, created_at
			) VALUES (9002, 1000, 2000, 20, 100, 120, 0, 2100)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO activity_snapshot(
				id, time_ms, activity_type, confidence, is_transition, created_at
			) VALUES (9003, 3000, 7, 88, 1, 3100)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO pressure_sample(
				id, time_ms, elapsed_realtime_nanos, pressure_hpa, altitude_m,
				bucket_id, created_at
			) VALUES (9004, 4000, 4444, 1001.25, 100.5, NULL, 4100)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO cell_sample(
				id, time_ms, cell_id, lac, mcc, mnc, network_type,
				signal_strength, lat_e7, lon_e7, provenance, created_at, legacy_lat, legacy_lon
			) VALUES (9005, 5000, 111, 22, 230, 1, 13, 30, NULL, NULL, 'DIRECT', 5100, 50.0, 14.0)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO wifi_observation(
				id, time_ms, bssid, ssid, capabilities, frequency, level,
				lat_e7, lon_e7, provenance, created_at, legacy_lat, legacy_lon
			) VALUES (9006, 6000, 'AA:BB:CC:DD:EE:FF', 'edge', '[WPA3]', 5955, -40,
				NULL, NULL, 'DIRECT', 6100, 49.9, 13.9)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO session_segment(
				id, start_time_ms, end_time_ms, distance_m, steps, primary_activity,
				activity_confidence, sample_count, source, inference_version, created_at,
				has_distance_anomaly, legacy_activity_id
			) VALUES (
				9007, 7000, 8000, 12.5, 20, NULL,
				88, 2, 'LEGACY_MIGRATION', 'v26', 8100,
				0, 7
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO daily_summary(
				date_epoch_day, total_distance_m, total_steps, total_duration_ms,
				trip_count, active_tracking_ms, last_updated_ms, created_at
			) VALUES (20000, 12.5, 20, 1000, 1, 900, 7000, 7000)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO achievement_progress(
				id, achievement_id, current_value, target_value, tier,
				unlocked_at, updated_at, notified_at
			) VALUES (9008, 'retired-contract', 7, 10, NULL, NULL, 7100, NULL)
			""".trimIndent(),
		)
	}

	private fun seedLargeLocationHistory(
		db: SupportSQLiteDatabase,
		rowCount: Int,
	) {
		db.execSQL(
			"""
			WITH RECURSIVE sample_index(value) AS (
				SELECT 0
				UNION ALL
				SELECT value + 1 FROM sample_index WHERE value + 1 < $rowCount
			)
			INSERT INTO location_sample(
				id, time_ms, elapsed_realtime_nanos, lat_e7, lon_e7,
				alt_m, raw_gps_alt_m, h_acc_m, v_acc_m, speed_mps,
				speed_accuracy_mps, provider, quality, motion_state, policy,
				bucket_id, created_at, legacy_lat, legacy_lon, legacy_alt_m
			)
			SELECT
				$LARGE_LOCATION_FIRST_ID + value,
				1700200000000 + value * 1000,
				5000000000 + value * 1000000000,
				NULL,
				NULL,
				NULL,
				NULL,
				5.0,
				8.0,
				1.5,
				0.5,
				'gps',
				'HIGH',
				NULL,
				NULL,
				NULL,
				1700200000100 + value * 1000,
				50.0 + (value % 1000) / 100000.0,
				14.0 + (value % 1000) / 100000.0,
				300.0 + (value % 50)
			FROM sample_index
			""".trimIndent(),
		)
	}

	private fun prepareReleasedV26(seed: (SupportSQLiteDatabase) -> Unit) {
		copyFixtureToLegacy()
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(LEGACY_DATABASE_NAME)
				.callback(object : SupportSQLiteOpenHelper.Callback(RELEASED_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) = error("Fixture was not copied")

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						oldVersion shouldBe FIXTURE_DATABASE_VERSION
						newVersion shouldBe RELEASED_DATABASE_VERSION
						var current = oldVersion
						migrationsToV26.forEach { migration ->
							migration.startVersion shouldBe current
							migration.migrate(db)
							current = migration.endVersion
						}
						current shouldBe newVersion
					}
				})
				.build(),
		)
		try {
			val raw = helper.writableDatabase
			deleteAllApplicationRows(raw)
			seed(raw)
		} finally {
			helper.close()
		}
	}

	private fun buildActive(): AppDatabase =
		Room.databaseBuilder(context, AppDatabase::class.java, ACTIVE_DATABASE_NAME)
			.addMigrations(*AppDatabase.activeMigrations)
			.addCallback(
				LegacyImportRoomCallback(
					context,
					AppDatabase.legacyPublicMigrationsThroughV26,
					FrameworkSQLiteOpenHelperFactory(),
				),
			)
			.allowMainThreadQueries()
			.build()
			.also(roomDatabases::add)

	private fun openActive(): SupportSQLiteDatabase = buildActive().openHelper.writableDatabase

	private fun editLegacy(block: (SQLiteDatabase) -> Unit) {
		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READWRITE,
		).use(block)
	}

	private fun copyFixtureToLegacy() {
		context.deleteDatabase(LEGACY_DATABASE_NAME)
		val target = context.getDatabasePath(LEGACY_DATABASE_NAME)
		target.parentFile?.mkdirs()
		checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)) {
			"Missing release fixture: $FIXTURE_RESOURCE"
		}.use { input -> target.outputStream().use(input::copyTo) }
	}

	private fun deleteAllApplicationRows(db: SupportSQLiteDatabase) {
		db.execSQL("PRAGMA foreign_keys = OFF")
		listTables(db).forEach { table -> db.execSQL("DELETE FROM `$table`") }
		db.execSQL("PRAGMA foreign_keys = ON")
	}

	private fun listTables(db: SupportSQLiteDatabase): Set<String> = db.query(
		"""
		SELECT name FROM sqlite_master
		WHERE type = 'table'
			AND name NOT LIKE 'sqlite_%'
			AND name NOT LIKE 'android_%'
			AND name != 'room_master_table'
		""".trimIndent(),
	).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

	private fun count(db: SupportSQLiteDatabase, table: String): Long =
		db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0)
		}

	private fun longValue(db: SupportSQLiteDatabase, query: String): Long =
		db.query(query).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0)
		}

	private fun stringValue(db: SupportSQLiteDatabase, query: String): String =
		db.query(query).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}

	private fun androidCount(db: SQLiteDatabase, table: String): Long =
		db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0)
		}

	private fun androidStringValue(db: SQLiteDatabase, query: String): String =
		db.rawQuery(query, null).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}

	private fun rebuildAsReleasedV26(db: SupportSQLiteDatabase) {
		releasedV26Tables.forEach { schema ->
			val migratedTable = "${schema.name}_with_unreleased_columns"
			val projectedColumns = schema.columns.joinToString(", ") { "`$it`" }
			db.execSQL("ALTER TABLE `${schema.name}` RENAME TO `$migratedTable`")
			db.execSQL(schema.createSql)
			db.execSQL(
				"INSERT INTO `${schema.name}` ($projectedColumns) " +
					"SELECT $projectedColumns FROM `$migratedTable`",
			)
			db.execSQL("DROP TABLE `$migratedTable`")
			schema.indexSql.values.forEach(db::execSQL)

			val actualColumns = db.query("PRAGMA table_info(`${schema.name}`)").use { cursor ->
				buildList {
					val nameIndex = cursor.getColumnIndexOrThrow("name")
					while (cursor.moveToNext()) add(cursor.getString(nameIndex))
				}
			}
			actualColumns shouldBe schema.columns
			val actualIndexes = db.query("PRAGMA index_list(`${schema.name}`)").use { cursor ->
				buildSet {
					val nameIndex = cursor.getColumnIndexOrThrow("name")
					while (cursor.moveToNext()) add(cursor.getString(nameIndex))
				}
			}
			actualIndexes shouldBe schema.indexSql.keys
		}
	}

	private fun sourceIdentity(db: SupportSQLiteDatabase, table: String, id: Long): String? =
		db.query("SELECT source_signal_id FROM `$table` WHERE id = ?", arrayOf(id)).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0)
		}

	private fun sourceItemIndex(db: SupportSQLiteDatabase, table: String, id: Long): Int =
		db.query("SELECT source_item_index FROM `$table` WHERE id = ?", arrayOf(id)).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getInt(0)
		}

	private fun coordinates(
		db: SupportSQLiteDatabase,
		table: String,
		id: Long,
	): Pair<Int, Int> = db.query(
		"SELECT lat_e7, lon_e7 FROM `$table` WHERE id = ?",
		arrayOf(id),
	).use { cursor ->
		cursor.moveToFirst() shouldBe true
		cursor.getInt(0) to cursor.getInt(1)
	}

	private fun deleteTestDatabases() {
		context.deleteDatabase(LEGACY_DATABASE_NAME)
		context.deleteDatabase(ACTIVE_DATABASE_NAME)
		context.deleteDatabase(STAGING_DATABASE_NAME)
	}

	private data class ReleasedV26Table(
		val name: String,
		val columns: List<String>,
		val createSql: String,
		val indexSql: Map<String, String>,
	)

	private companion object {
		const val FIXTURE_RESOURCE = "baseline/2024.1/main_database.db"
		const val FIXTURE_DATABASE_VERSION = 10
		const val RELEASED_DATABASE_VERSION = 26
		const val STAGING_DATABASE_NAME = "legacy_import_v26_staging"
		const val LEGACY_PREFERENCES = "legacy_database_v27"
		const val LARGE_LOCATION_ROW_COUNT = 25_000
		const val LARGE_LOCATION_FIRST_ID = 100_000L
		const val LARGE_IMPORT_MAX_MILLIS = 60_000L

		val releasedV26Tables = listOf(
			ReleasedV26Table(
				name = "location_sample",
				columns = listOf(
					"id", "time_ms", "elapsed_realtime_nanos", "lat_e7", "lon_e7", "alt_m",
					"raw_gps_alt_m", "h_acc_m", "v_acc_m", "speed_mps", "speed_accuracy_mps",
					"provider", "quality", "motion_state", "policy", "bucket_id", "created_at",
				),
				createSql = """
					CREATE TABLE IF NOT EXISTS `location_sample` (
						`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
						`time_ms` INTEGER NOT NULL, `elapsed_realtime_nanos` INTEGER NOT NULL,
						`lat_e7` INTEGER, `lon_e7` INTEGER, `alt_m` REAL, `raw_gps_alt_m` REAL,
						`h_acc_m` REAL, `v_acc_m` REAL, `speed_mps` REAL,
						`speed_accuracy_mps` REAL, `provider` TEXT NOT NULL, `quality` TEXT NOT NULL,
						`motion_state` TEXT, `policy` TEXT, `bucket_id` INTEGER, `created_at` INTEGER NOT NULL
					)
				""".trimIndent(),
				indexSql = mapOf(
					"idx_location_sample_time" to
						"CREATE INDEX IF NOT EXISTS `idx_location_sample_time` ON `location_sample` (`time_ms`)",
					"idx_location_sample_coords" to
						"CREATE INDEX IF NOT EXISTS `idx_location_sample_coords` ON `location_sample` (`lat_e7`, `lon_e7`)",
					"idx_location_sample_bucket" to
						"CREATE INDEX IF NOT EXISTS `idx_location_sample_bucket` ON `location_sample` (`bucket_id`)",
				),
			),
			ReleasedV26Table(
				name = "cell_sample",
				columns = listOf(
					"id", "time_ms", "cell_id", "lac", "mcc", "mnc", "network_type",
					"signal_strength", "lat_e7", "lon_e7", "provenance", "created_at",
				),
				createSql = """
					CREATE TABLE IF NOT EXISTS `cell_sample` (
						`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time_ms` INTEGER NOT NULL,
						`cell_id` INTEGER NOT NULL, `lac` INTEGER NOT NULL, `mcc` INTEGER NOT NULL,
						`mnc` INTEGER NOT NULL, `network_type` INTEGER NOT NULL,
						`signal_strength` INTEGER NOT NULL, `lat_e7` INTEGER, `lon_e7` INTEGER,
						`provenance` TEXT NOT NULL, `created_at` INTEGER NOT NULL
					)
				""".trimIndent(),
				indexSql = mapOf(
					"idx_cell_sample_time" to
						"CREATE INDEX IF NOT EXISTS `idx_cell_sample_time` ON `cell_sample` (`time_ms`)",
					"idx_cell_sample_cell_id" to
						"CREATE INDEX IF NOT EXISTS `idx_cell_sample_cell_id` ON `cell_sample` (`cell_id`)",
					"idx_cell_sample_coords" to
						"CREATE INDEX IF NOT EXISTS `idx_cell_sample_coords` ON `cell_sample` (`lat_e7`, `lon_e7`)",
				),
			),
			ReleasedV26Table(
				name = "wifi_observation",
				columns = listOf(
					"id", "time_ms", "bssid", "ssid", "capabilities", "frequency", "level",
					"lat_e7", "lon_e7", "provenance", "created_at",
				),
				createSql = """
					CREATE TABLE IF NOT EXISTS `wifi_observation` (
						`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time_ms` INTEGER NOT NULL,
						`bssid` TEXT NOT NULL, `ssid` TEXT NOT NULL, `capabilities` TEXT NOT NULL,
						`frequency` INTEGER NOT NULL, `level` INTEGER NOT NULL, `lat_e7` INTEGER,
						`lon_e7` INTEGER, `provenance` TEXT NOT NULL, `created_at` INTEGER NOT NULL
					)
				""".trimIndent(),
				indexSql = mapOf(
					"idx_wifi_obs_time" to
						"CREATE INDEX IF NOT EXISTS `idx_wifi_obs_time` ON `wifi_observation` (`time_ms`)",
					"idx_wifi_obs_bssid" to
						"CREATE INDEX IF NOT EXISTS `idx_wifi_obs_bssid` ON `wifi_observation` (`bssid`)",
					"idx_wifi_obs_coords" to
						"CREATE INDEX IF NOT EXISTS `idx_wifi_obs_coords` ON `wifi_observation` (`lat_e7`, `lon_e7`)",
				),
			),
			ReleasedV26Table(
				name = "session_segment",
				columns = listOf(
					"id", "start_time_ms", "end_time_ms", "distance_m", "steps",
					"primary_activity", "activity_confidence", "sample_count", "source",
					"inference_version", "created_at", "has_distance_anomaly",
				),
				createSql = """
					CREATE TABLE IF NOT EXISTS `session_segment` (
						`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
						`start_time_ms` INTEGER NOT NULL, `end_time_ms` INTEGER NOT NULL,
						`distance_m` REAL NOT NULL, `steps` INTEGER, `primary_activity` INTEGER,
						`activity_confidence` INTEGER, `sample_count` INTEGER NOT NULL,
						`source` TEXT NOT NULL, `inference_version` TEXT, `created_at` INTEGER NOT NULL,
						`has_distance_anomaly` INTEGER NOT NULL DEFAULT 0
					)
				""".trimIndent(),
				indexSql = mapOf(
					"idx_session_segment_time_range" to
						"CREATE INDEX IF NOT EXISTS `idx_session_segment_time_range` ON `session_segment` (`start_time_ms`, `end_time_ms`)",
					"idx_session_segment_end_time_ms" to
						"CREATE INDEX IF NOT EXISTS `idx_session_segment_end_time_ms` ON `session_segment` (`end_time_ms`)",
					"idx_session_segment_source" to
						"CREATE INDEX IF NOT EXISTS `idx_session_segment_source` ON `session_segment` (`source`)",
					"idx_session_segment_primary_activity" to
						"CREATE INDEX IF NOT EXISTS `idx_session_segment_primary_activity` ON `session_segment` (`primary_activity`)",
				),
			),
		)

		val migrationsToV26: List<Migration> =
			AppDatabase.legacyPublicMigrationsThroughV26.filter {
			it.startVersion >= FIXTURE_DATABASE_VERSION &&
				it.endVersion <= RELEASED_DATABASE_VERSION
			}
	}
}
