package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
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
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** End-to-end coverage for the public v26 vault -> fresh current-schema import boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // This fixture proves the complete public v26 import boundary together.
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

	// Keep one fresh database open and its writer, trigger and empty-history assertions together.
	@Suppress("LongMethod")
	@Test
	fun `clean install creates only the active database`() {
		val raw = openActive()

		raw.version shouldBe CURRENT_DATABASE_VERSION
		context.getDatabasePath(ACTIVE_DATABASE_NAME).exists() shouldBe true
		context.getDatabasePath(LEGACY_DATABASE_NAME).exists() shouldBe false
		LegacyDatabaseRepository(context).currentState().database shouldBe null
		count(raw, "source_destination_owner") shouldBe 5L
		stringValue(
			raw,
			"SELECT owner FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_LOCATION} AND destination = " +
				"'${SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION}'",
		) shouldBe SourceDestinationOwnerEntity.OWNER_EXISTING_LOCATION_CANONICAL_PIPELINE
		longValue(
			raw,
			"SELECT owner_generation FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_LOCATION} AND destination = " +
				"'${SourceDestinationOwnerEntity.DESTINATION_SESSION_LOCATION}'",
		) shouldBe SourceDestinationOwnerEntity.INITIAL_EXISTING_LOCATION_GENERATION
		stringValue(
			raw,
			"SELECT owner FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_STEPS} AND destination = " +
				"'${SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS}'",
		) shouldBe SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS
		stringValue(
			raw,
			"SELECT owner FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_ACTIVITY}",
		) shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_ACTIVITY_SNAPSHOT
		longValue(
			raw,
			"SELECT owner_generation FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_ACTIVITY}",
		) shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		stringValue(
			raw,
			"SELECT owner FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_PRESSURE}",
		) shouldBe SourceDestinationOwnerEntity.OWNER_LEGACY_PRESSURE_SAMPLE
		longValue(
			raw,
			"SELECT owner_generation FROM source_destination_owner WHERE source_kind = " +
				"${SourceDestinationOwnerEntity.SOURCE_PRESSURE}",
		) shouldBe SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION
		count(raw, "ambient_steps_fact_revision") shouldBe 0L
		count(raw, "ambient_steps_import_cursor") shouldBe 0L
		count(raw, "ambient_steps_import_gap") shouldBe 0L
		count(raw, "ambient_steps_import_authority_transition") shouldBe 0L
		count(raw, "ambient_wifi_authority") shouldBe 0L
		count(raw, "ambient_cell_authority") shouldBe 0L
		count(raw, "wifi_selected_deletion_receipt") shouldBe 0L
		count(raw, "imported_cell_entry_deletion_receipt") shouldBe 0L
		count(raw, "source_capture_admission_barrier") shouldBe 0L
		count(raw, "source_run_retirement") shouldBe 0L
		count(raw, "pending_signal") shouldBe 0L
		assertPendingPressureColumns(raw)
		listOf(
			"validate_pending_signal_writer_owners_insert",
			"validate_pending_signal_writer_owners_update",
			"validate_imported_pressure_source_erase_owner_insert",
			"validate_imported_pressure_source_erase_owner_update",
			"reject_pressure_fact_retention_resurrection",
		).forEach { trigger ->
			longValue(
				raw,
				"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '$trigger'",
			) shouldBe 1L
		}
		count(raw, "pressure_fact_revision") shouldBe 0L
		count(raw, "pressure_fact_retention_authority") shouldBe 0L
		count(raw, "pressure_fact_retention_revision_authority") shouldBe 0L
		count(raw, "activity_captured_registration_plan") shouldBe 0L
		count(raw, "activity_captured_window_revision") shouldBe 0L
	}

	@Test
	fun `populated released v12 database imports its observations without altering the original`() {
		createReleasedV12Database()

		val active = openActive()
		active.version shouldBe CURRENT_DATABASE_VERSION
		hasCompletedLegacyImport(active) shouldBe true
		count(active, "location_sample") shouldBe 1L
		count(active, "location_observation") shouldBe 1L
		count(active, "activity_snapshot") shouldBe 1L
		count(active, "session_segment") shouldBe 1L
		count(active, "wifi_observation") shouldBe 1L
		count(active, "cell_sample") shouldBe 1L
		count(active, "legacy_wifi_child") shouldBe 1L
		count(active, "legacy_cell_child") shouldBe 1L
		stringValue(active, "SELECT ssid FROM legacy_wifi_child") shouldBe "Released network"
		longValue(active, "SELECT cell_id FROM legacy_cell_child") shouldBe 314159L

		SQLiteDatabase.openDatabase(
			context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null,
			SQLiteDatabase.OPEN_READONLY,
		).use { original ->
			original.version shouldBe 12
			androidCount(original, "wifi_data") shouldBe 1L
			androidCount(original, "cell_location") shouldBe 1L
		}
	}

	@Test
	fun `released v12 custom label id is not a detected Activity type`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { original ->
			original.execSQL("UPDATE activity SET name = 'My cycling label' WHERE id = 7")
		}

		val database = buildActive()
		val active = database.openHelper.writableDatabase
		hasCompletedLegacyImport(active) shouldBe true
		val trip = requireNotNull(database.tripDao().getById(201))
		trip.primaryActivity shouldBe null
		trip.source shouldBe com.adsamcik.tracker.shared.model.SegmentSource.LEGACY_MIGRATION
		trip.steps shouldBe 20
		stringValue(active, "SELECT name FROM activity WHERE id = 7") shouldBe "My cycling label"
		count(active, "logical_tracking_session") shouldBe 0L

		SQLiteDatabase.openDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null, SQLiteDatabase.OPEN_READONLY).use { original ->
			original.version shouldBe 12
			original.rawQuery("SELECT session_activity_id FROM tracker_session WHERE id = 201", null).use { row ->
				row.moveToFirst() shouldBe true
				row.getLong(0) shouldBe 7L
			}
			androidStringValue(original, "SELECT name FROM activity WHERE id = 7") shouldBe "My cycling label"
		}
	}

	@Test
	fun `compatible v26 detected Activity survives a different session label id`(): Unit = runBlocking {
		prepareReleasedV26 { source ->
			seedV26EdgeRows(source)
			source.execSQL("UPDATE session_segment SET primary_activity = 7, legacy_activity_id = 123 WHERE id = 9007")
		}

		val database = buildActive()
		val active = database.openHelper.writableDatabase
		hasCompletedLegacyImport(active) shouldBe true
		val trip = requireNotNull(database.tripDao().getById(9007))
		trip.primaryActivity shouldBe 7
		trip.activityConfidence shouldBe 88
		SQLiteDatabase.openDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME).path,
			null, SQLiteDatabase.OPEN_READONLY).use { original ->
			original.rawQuery("SELECT primary_activity, legacy_activity_id FROM session_segment WHERE id = 9007", null).use { row ->
				row.moveToFirst() shouldBe true
				row.getInt(0) shouldBe 7
				row.getLong(1) shouldBe 123L
			}
		}
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
		count(raw, "pressure_fact_revision") shouldBe 0L
		count(raw, "pressure_fact_retention_authority") shouldBe 0L
		count(raw, "pressure_fact_retention_revision_authority") shouldBe 0L
		count(raw, "pending_signal") shouldBe 0L
		assertPendingPressureColumns(raw)
		longValue(raw, "SELECT COUNT(*) FROM session_segment WHERE id = 9007 " +
			"AND primary_activity IS NULL") shouldBe 1L

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

	@Test
	fun `released originals retain all seventeen fields and the session label adapter`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { original ->
			original.execSQL("UPDATE location_data SET lat = 50.123456789, lon = 14.234567891, alt = 221.125 WHERE id = 101")
			original.execSQL("UPDATE wifi_data SET latitude = 50.345678912, longitude = 14.456789123, altitude = 222.25, first_seen = 1700099999000")
			original.execSQL("UPDATE cell_location SET mnc = '001', lat = 50.567891234, lon = 14.678912345, alt = 223.375 WHERE id = 301")
			original.execSQL("UPDATE tracker_session SET user_initiated = 0, distance_on_foot = 24.25, distance_in_vehicle = 100.75 WHERE id = 201")
			original.execSQL("UPDATE activity SET name = 'My cycling label' WHERE id = 7")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		val point = database.locationSampleDao().byIds(listOf(101)).single()
		point.legacyLat shouldBe 50.123456789
		point.legacyLon shouldBe 14.234567891
		point.legacyAltM shouldBe 221.125
		val wifi = database.wifiObservationDao().getChunkBetweenOrdered(0, Long.MAX_VALUE, null, null, 10).single()
		wifi.legacyFirstSeenMs shouldBe 1_700_099_999_000L
		wifi.legacyLat shouldBe 50.345678912
		wifi.legacyLon shouldBe 14.456789123
		wifi.legacyAltM shouldBe 222.25
		val cell = database.cellSampleDao().getChunkBetweenOrdered(0, Long.MAX_VALUE, null, null, 10).single()
		cell.legacySourceId shouldBe 301L
		cell.legacyMcc shouldBe "230"
		cell.legacyMnc shouldBe "001"
		cell.legacyLat shouldBe 50.567891234
		cell.legacyLon shouldBe 14.678912345
		cell.legacyAltM shouldBe 223.375
		val segment = requireNotNull(database.sessionSegmentDao().getById(201))
		segment.legacyUserInitiated shouldBe false
		segment.legacyDistanceOnFootM shouldBe 24.25
		segment.legacyDistanceInVehicleM shouldBe 100.75
		segment.legacyActivityId shouldBe 7L
		segment.primaryActivity shouldBe null
		segment.logicalTrackingId shouldBe null
		segment.serviceRunId shouldBe null
		val annotation = requireNotNull(database.organizationAnnotationDao().byAdapter("segment:201"))
		annotation.activityLabelId shouldBe 7L
		annotation.startTimeMs shouldBe null
		annotation.endTimeMs shouldBe null
		annotation.displayZoneId shouldBe null
		annotation.name shouldBe null
		annotation.hidden shouldBe false
		count(raw, "logical_tracking_session") shouldBe 0L
		stringValue(raw, "SELECT name FROM activity WHERE id = 7") shouldBe "My cycling label"
		SQLiteDatabase.openDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME).path, null,
			SQLiteDatabase.OPEN_READONLY).use { original ->
			original.version shouldBe 12
			original.rawQuery("SELECT lat, lon, alt FROM location_data WHERE id = 101", null).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getDouble(0) shouldBe 50.123456789
				cursor.getDouble(1) shouldBe 14.234567891
				cursor.getDouble(2) shouldBe 221.125
			}
		}
	}

	@Test
	fun `unfinished and short released sessions counts and operator names survive as typed records`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { original ->
			original.execSQL("""
				INSERT INTO tracker_session(id, start, `end`, user_initiated, collections,
					distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
				VALUES (202, 500, 700, 1, 1, 8.5, 3.25, 5.25, 2, 7),
					(203, 800, -1, 0, 3, 9.5, 4.25, 5.25, 3, 987)
			""".trimIndent())
			original.execSQL("INSERT INTO network_operator(mcc, mnc, name) VALUES ('230', '001', NULL)")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		val short = requireNotNull(database.legacyPreservedDataDao().rejectedSession(202))
		short.start shouldBe 500L
		short.end shouldBe 700L
		short.userInitiated shouldBe true
		short.collections shouldBe 1
		short.distance shouldBe 8.5
		short.distanceOnFoot shouldBe 3.25
		short.distanceInVehicle shouldBe 5.25
		short.steps shouldBe 2
		short.sessionActivityId shouldBe 7L
		val unfinished = requireNotNull(database.legacyPreservedDataDao().rejectedSession(203))
		unfinished.end shouldBe -1L
		unfinished.sessionActivityId shouldBe 987L
		database.sessionSegmentDao().getById(202) shouldBe null
		database.sessionSegmentDao().getById(203) shouldBe null
		database.organizationAnnotationDao().byAdapter("segment:203") shouldBe null
		val wifiCount = database.legacyPreservedDataDao().wifiCounts(1_700_100_003_000L, 1_700_100_003_001L, 10).single()
		wifiCount.id shouldBe 401L
		wifiCount.time shouldBe 1_700_100_003_000L
		wifiCount.count shouldBe 3
		wifiCount.lat shouldBe 50.1
		wifiCount.lon shouldBe 14.2
		wifiCount.alt shouldBe 220.0
		database.legacyPreservedDataDao().wifiCounts(wifiCount.time + 1, wifiCount.time + 2, 10) shouldBe emptyList()
		requireNotNull(database.legacyPreservedDataDao().networkOperator("230", "01")).name shouldBe "Carrier"
		requireNotNull(database.legacyPreservedDataDao().networkOperator("230", "001")).name shouldBe null
		count(raw, "wifi_observation") shouldBe 1L
		count(raw, "legacy_wifi_child") shouldBe 1L
		LegacyDatabaseRepository(context).currentState().report?.importedRows?.get("legacy_rejected_tracker_session") shouldBe 2L
		LegacyDatabaseRepository(context).currentState().report?.importedRows?.get("legacy_location_wifi_count") shouldBe 1L
		LegacyDatabaseRepository(context).currentState().report?.importedRows?.get("network_operator") shouldBe 2L
	}

	@Test
	fun `normalized input without optional originals or compatibility tables still imports`(): Unit = runBlocking {
		prepareReleasedV26 { source ->
			seedV26EdgeRows(source)
			source.execSQL("UPDATE location_sample SET lat_e7 = 501000000, lon_e7 = 142000000, alt_m = 321.25")
			source.execSQL("UPDATE cell_sample SET lat_e7 = 500000000, lon_e7 = 140000000")
			source.execSQL("UPDATE wifi_observation SET lat_e7 = 499000000, lon_e7 = 139000000")
			rebuildAsReleasedV26(source)
			source.execSQL("DROP TABLE legacy_rejected_tracker_session")
			source.execSQL("DROP TABLE legacy_location_wifi_count")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		hasCompletedLegacyImport(raw) shouldBe true
		listOf("location_sample", "wifi_observation", "cell_sample", "session_segment").forEach { table ->
			raw.query("PRAGMA table_info(`$table`)").use { cursor ->
				val names = buildList { while (cursor.moveToNext()) {
					val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
					if (name.startsWith("legacy_")) add(name)
				} }
				names.isNotEmpty() shouldBe true
				raw.query("SELECT ${names.joinToString { "`$it`" }} FROM `$table`").use { row ->
					row.moveToFirst() shouldBe true
					for (index in names.indices) row.isNull(index) shouldBe true
				}
			}
		}
		count(raw, "legacy_rejected_tracker_session") shouldBe 0L
		count(raw, "legacy_location_wifi_count") shouldBe 0L
		count(raw, "organization_annotation") shouldBe 0L
		coordinates(raw, "location_sample", 9001) shouldBe (501_000_000 to 142_000_000)
		LegacyDatabaseRepository(context).currentState().report?.importedRows?.get("legacy_location_wifi_count") shouldBe 0L
	}

	@Test
	fun `missing label remains a raw reference while real detected activity stays separate`(): Unit = runBlocking {
		prepareReleasedV26 { source ->
			seedV26EdgeRows(source)
			source.execSQL("UPDATE session_segment SET primary_activity = 7, legacy_activity_id = 123 WHERE id = 9007")
		}
		val database = buildActive()
		database.openHelper.writableDatabase
		val segment = requireNotNull(database.sessionSegmentDao().getById(9007))
		segment.primaryActivity shouldBe 7
		segment.legacyActivityId shouldBe 123L
		database.activityDao().get(123) shouldBe null
		requireNotNull(database.organizationAnnotationDao().byAdapter("segment:9007")).activityLabelId shouldBe 123L
	}

	@Test
	fun `reopening repeats no import and preserves a user session label edit`(): Unit = runBlocking {
		createReleasedV12Database()
		val database = buildActive()
		database.openHelper.writableDatabase
		val annotation = requireNotNull(database.organizationAnnotationDao().byAdapter("segment:201"))
		database.organizationAnnotationDao().update(annotation.copy(revision = 2, name = "Home ride", activityLabelId = null)) shouldBe 1
		database.close()
		roomDatabases.remove(database)
		val reopened = buildActive()
		val reopenedRaw = reopened.openHelper.writableDatabase
		count(reopenedRaw, "organization_annotation") shouldBe 1L
		count(reopenedRaw, "legacy_location_wifi_count") shouldBe 1L
		count(reopenedRaw, "network_operator") shouldBe 1L
		val edited = requireNotNull(reopened.organizationAnnotationDao().byAdapter("segment:201"))
		edited.name shouldBe "Home ride"
		edited.activityLabelId shouldBe null
		edited.revision shouldBe 2L
		hasCompletedLegacyImport(reopenedRaw) shouldBe true
	}

	@Test
	fun `actual full clear removes preserved data without replaying the retained original`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { source ->
			source.execSQL("""INSERT INTO tracker_session VALUES (202, 500, 700, 1, 1, 8.5, 3.25, 5.25, 2, 7)""")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		hasCompletedLegacyImport(raw) shouldBe true
		database.sourceEvidenceStateDao().ensure()
		val epoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
		AppDatabase.deleteAllCollectedDataForTesting(database, "released-preservation-clear", epoch + 1, null, 1_700_100_100_000L)
		listOf("location_sample", "wifi_observation", "cell_sample", "session_segment", "organization_annotation",
			"legacy_rejected_tracker_session", "legacy_location_wifi_count", "network_operator").forEach { table ->
			count(raw, table) shouldBe 0L
		}
		hasCompletedLegacyImport(raw) shouldBe true
		database.close()
		roomDatabases.remove(database)
		val reopened = openActive()
		count(reopened, "legacy_location_wifi_count") shouldBe 0L
		count(reopened, "network_operator") shouldBe 0L
		count(reopened, "session_segment") shouldBe 0L
		hasCompletedLegacyImport(reopened) shouldBe true
		SQLiteDatabase.openDatabase(context.getDatabasePath(LEGACY_DATABASE_NAME).path, null,
			SQLiteDatabase.OPEN_READONLY).use { original ->
			original.version shouldBe 12
			androidCount(original, "tracker_session") shouldBe 2L
			androidCount(original, "location_wifi_count") shouldBe 1L
		}
	}

	@Test
	fun `existing parent retention removes originals and mirrors while raw unknown bounds stay honest`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { source ->
			source.execSQL("""INSERT INTO tracker_session VALUES
				(202, 500, 700, 1, 1, 8.5, 3.25, 5.25, 2, 7),
				(203, 800, -1, 0, 3, 9.5, 4.25, 5.25, 3, 7),
				(204, 1700100200000, 1700100300000, 1, 1, 1.0, 1.0, 0.0, 1, 7)""")
			source.execSQL("INSERT INTO location_wifi_count VALUES (402, 1700100200000, 2, 50.2, 14.3, NULL)")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		val cutoff = 1_700_100_100_001L
		database.withTransaction {
			database.organizationAnnotationDao().suppressExpiredOverrides(cutoff, cutoff)
			database.sessionSegmentDao().deleteOlderThan(cutoff)
			database.legacyPreservedDataDao().deleteRejectedSessionsOlderThan(cutoff)
			database.organizationAnnotationDao().pruneUnusedSuppression()
			database.locationSampleDao().deleteOlderThan(cutoff)
			database.wifiObservationDao().deleteOlderThan(cutoff)
			database.cellSampleDao().deleteOlderThan(cutoff)
			database.legacyPreservedDataDao().deleteWifiCountsOlderThan(cutoff)
		}
		listOf("location_sample", "wifi_observation", "cell_sample", "session_segment", "organization_annotation",
			"legacy_wifi_child", "legacy_cell_child").forEach { table -> count(raw, table) shouldBe 0L }
		database.legacyPreservedDataDao().rejectedSession(202) shouldBe null
		requireNotNull(database.legacyPreservedDataDao().rejectedSession(203)).end shouldBe -1L
		requireNotNull(database.legacyPreservedDataDao().rejectedSession(204)).end shouldBe 1_700_100_300_000L
		database.legacyPreservedDataDao().wifiCounts(0, Long.MAX_VALUE, 10).map { it.id } shouldBe listOf(402L)
		requireNotNull(database.legacyPreservedDataDao().networkOperator("230", "01")).name shouldBe "Carrier"
	}

	@Test
	fun `count-only Wi-Fi pages by measured time and id without creating network rows`(): Unit = runBlocking {
		createReleasedV12Database()
		editLegacy { source ->
			source.execSQL("INSERT INTO location_wifi_count VALUES (402, 1700100003000, 8, 50.2, 14.3, NULL)")
		}
		val database = buildActive()
		val raw = database.openHelper.writableDatabase
		val first = database.legacyPreservedDataDao().wifiCounts(1_700_100_003_000L, 1_700_100_003_001L, 1).single()
		first.id shouldBe 401L
		val second = database.legacyPreservedDataDao().wifiCounts(first.time, first.time + 1, 1, first.time, first.id).single()
		second.id shouldBe 402L
		second.count shouldBe 8
		second.alt shouldBe null
		database.legacyPreservedDataDao().wifiCounts(second.time, second.time + 1, 1, second.time, second.id) shouldBe emptyList()
		count(raw, "wifi_observation") shouldBe 1L
		count(raw, "legacy_wifi_child") shouldBe 1L
	}

	@Test
	fun `new current rows leave original released values unknown`() {
		val raw = openActive()
		raw.execSQL("INSERT INTO location_sample(time_ms, elapsed_realtime_nanos, provider, quality, created_at) VALUES (1000, 0, 'gps', 'HIGH', 1000)")
		raw.execSQL("INSERT INTO wifi_observation(time_ms, bssid, ssid, capabilities, frequency, level, provenance, created_at) VALUES (1000, '02:00:00:00:00:01', 'Current', '', 2412, -50, 'UNKNOWN', 1000)")
		raw.execSQL("INSERT INTO cell_sample(time_ms, cell_id, lac, mcc, mnc, network_type, signal_strength, provenance, created_at) VALUES (1000, 5, 2, 230, 1, 13, -90, 'UNKNOWN', 1000)")
		raw.execSQL("INSERT INTO session_segment(start_time_ms, end_time_ms, distance_m, sample_count, source, created_at) VALUES (1000, 2000, 0.0, 0, 'USER_CREATED', 2000)")
		listOf("location_sample", "wifi_observation", "cell_sample", "session_segment").forEach { table ->
			raw.query("PRAGMA table_info(`$table`)").use { cursor ->
				val names = buildList { while (cursor.moveToNext()) {
					val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
					if (name.startsWith("legacy_")) add(name)
				} }
				raw.query("SELECT ${names.joinToString { "`$it`" }} FROM `$table`").use { row ->
					row.moveToFirst() shouldBe true
					for (index in names.indices) row.isNull(index) shouldBe true
				}
			}
		}
		count(raw, "organization_annotation") shouldBe 0L
	}

	@Test
	fun `malformed optional compatibility table fails atomically and repairs on the same helper`() {
		prepareReleasedV26 { source ->
			seedV26EdgeRows(source)
			source.execSQL("ALTER TABLE legacy_location_wifi_count RENAME COLUMN count TO missing_count")
		}
		val database = buildActive()
		runCatching { database.openHelper.writableDatabase }.isFailure shouldBe true
		LegacyDatabaseRepository(context).currentState().importStatus shouldBe LegacyImportStatus.FAILED
		editLegacy { source -> source.execSQL("ALTER TABLE legacy_location_wifi_count RENAME COLUMN missing_count TO count") }
		val raw = database.openHelper.writableDatabase
		hasCompletedLegacyImport(raw) shouldBe true
		count(raw, "location_sample") shouldBe 1L
		count(raw, "organization_annotation") shouldBe 1L
		count(raw, "legacy_location_wifi_count") shouldBe 0L
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

	private fun createReleasedV12Database() {
		val relativeSchema = "schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/12.json"
		val schemaFile = sequenceOf(File(relativeSchema), File("core/base/$relativeSchema"))
			.firstOrNull(File::isFile) ?: error("Missing committed released v12 Room schema")
		val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
		schema.getInt("version") shouldBe 12
		schema.getString("identityHash") shouldBe "603376ce119f1b4f1d3e4866aec15b4e"
		val target = context.getDatabasePath(LEGACY_DATABASE_NAME)
		target.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(target, null).use { released ->
			val entities = schema.getJSONArray("entities")
			for (index in 0 until entities.length()) {
				val entity = entities.getJSONObject(index)
				val table = entity.getString("tableName")
				released.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
				val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
				for (indexOfIndex in 0 until indices.length()) {
					released.execSQL(
						indices.getJSONObject(indexOfIndex).getString("createSql")
							.replace("${'$'}{TABLE_NAME}", table),
					)
				}
			}
			val setup = schema.getJSONArray("setupQueries")
			for (index in 0 until setup.length()) released.execSQL(setup.getString(index))
			released.execSQL("INSERT INTO activity(id, name, iconName) VALUES (7, 'Walk', 'walk')")
			released.execSQL("""
				INSERT INTO location_data(id, time, lat, lon, activity, confidence)
				VALUES (101, 1700100000000, 50.1, 14.2, 7, 90)
			""".trimIndent())
			released.execSQL("""
				INSERT INTO tracker_session(id, start, `end`, user_initiated, collections,
					distance, distance_on_foot, distance_in_vehicle, steps, session_activity_id)
				VALUES (201, 1700099900000, 1700100100000, 1, 2, 125.0, 125.0, 0.0, 20, 7)
			""".trimIndent())
			released.execSQL("""
				INSERT INTO wifi_data(bssid, longitude, latitude, altitude, first_seen, last_seen,
					ssid, capabilities, frequency, level)
				VALUES ('02:00:00:00:00:01', 14.2, 50.1, 220.0, 1700100000000,
					1700100001000, 'Released network', '[WPA2]', 2412, -55)
			""".trimIndent())
			released.execSQL("INSERT INTO network_operator(mcc, mnc, name) VALUES ('230', '01', 'Carrier')")
			released.execSQL("""
				INSERT INTO cell_location(id, time, mcc, mnc, cell_id, type, asu, lat, lon, alt)
				VALUES (301, 1700100002000, '230', '01', 314159, 1, 18, 50.1, 14.2, 220.0)
			""".trimIndent())
			released.execSQL("""
				INSERT INTO location_wifi_count(id, time, count, lat, lon, alt)
				VALUES (401, 1700100003000, 3, 50.1, 14.2, 220.0)
			""".trimIndent())
			released.version = 12
		}
	}

	private fun buildActive(): AppDatabase =
		AppDatabase.fileBuilder(context, ACTIVE_DATABASE_NAME)
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

	private fun assertPendingPressureColumns(database: androidx.sqlite.db.SupportSQLiteDatabase) {
		database.query("PRAGMA table_info(pending_signal)").use { cursor ->
			val columns = buildMap {
				while (cursor.moveToNext()) {
					put(
						cursor.getString(cursor.getColumnIndexOrThrow("name")),
						cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) to
							cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
					)
				}
			}
			columns["pressure_writer_owner"] shouldBe (0 to null)
			columns["pressure_writer_owner_generation"] shouldBe (0 to null)
		}
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
