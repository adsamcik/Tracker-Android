package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Release-boundary coverage for the only supported post-385 migration.
 *
 * Every source database starts as the checked-in v10 release fixture and is upgraded through the
 * production migration chain to v26. This avoids hand-written partial schemas drifting away from
 * the database that versionCode 385 actually produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V26ToV27ReconciliationTest {
	private lateinit var context: Application
	private val roomDatabases = mutableListOf<RoomDatabase>()
	private val databaseNames = mutableSetOf<String>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
	}

	@After
	fun tearDown() {
		roomDatabases.forEach { runCatching { it.close() } }
		databaseNames.forEach(context::deleteDatabase)
		roomDatabases.clear()
		databaseNames.clear()
	}

	@Test
	fun `empty released v26 database migrates and validates the complete v27 schema`() {
		val name = prepareReleasedV26 { db -> deleteAllApplicationRows(db) }

		val raw = openCurrent(name).openHelper.writableDatabase

		raw.version shouldBe CURRENT_DATABASE_VERSION
		count(raw, "location_sample") shouldBe 0
		count(raw, "location_observation") shouldBe 0
		count(raw, "pending_signal") shouldBe 0
		count(raw, "achievement_progress") shouldBe 0
		count(raw, "source_evidence_state") shouldBe 1
		listTables(raw) shouldContain "import_job_receipt"
		listTables(raw) shouldContain "import_entry_receipt"
	}

	@Test
	fun `populated released v26 preserves rows and backfills every new evidence identity`() {
		val name = prepareReleasedV26(::seedV26EdgeRows)

		val raw = openCurrent(name).openHelper.writableDatabase

		raw.query(
			"""
			SELECT time_ms, lat_e7, lon_e7, alt_m, raw_gps_alt_m,
				received_elapsed_realtime_nanos, delivery_age_ms,
				acquisition_mode, request_priority, permission_precision,
				batch_index, batch_size, is_mock, estimator_version, calibration_version,
				source_signal_id, source_event_id, source_revision,
				alt_datum, alt_source, alt_conversion_status, raw_gps_alt_datum,
				alt_model_version
			FROM location_sample WHERE id = 9001
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 1_700_100_000_000L
			cursor.isNull(1) shouldBe true
			cursor.isNull(2) shouldBe true
			cursor.getDouble(3) shouldBe 321.25
			cursor.isNull(4) shouldBe true
			cursor.getLong(5) shouldBe 0L
			cursor.isNull(6) shouldBe true
			cursor.getString(7) shouldBe "UNKNOWN"
			cursor.getString(8) shouldBe "UNKNOWN"
			cursor.getString(9) shouldBe "UNKNOWN"
			cursor.getInt(10) shouldBe 0
			cursor.getInt(11) shouldBe 1
			cursor.getInt(12) shouldBe 0
			cursor.getInt(13) shouldBe 1
			cursor.getInt(14) shouldBe 0
			cursor.getString(15) shouldBe "legacy:location_sample:9001"
			cursor.isNull(16) shouldBe true
			cursor.getLong(17) shouldBe 0L
			cursor.getString(18) shouldBe "unknown_legacy"
			cursor.getString(19) shouldBe "unknown_legacy"
			cursor.getString(20) shouldBe "unknown_legacy"
			cursor.getString(21) shouldBe "unknown_legacy"
			cursor.getInt(22) shouldBe 0
		}

		raw.query(
			"""
			SELECT raw_alt_m, lat_e7, lon_e7, ingress_disposition,
				source_signal_id, source_event_id, callback_id, clock_domain_id,
				source_revision
			FROM location_observation WHERE fix_time_ms = 1700100000000
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.isNull(0) shouldBe true
			cursor.isNull(1) shouldBe true
			cursor.isNull(2) shouldBe true
			cursor.getString(3) shouldBe "MIGRATED_ACCEPTED"
			cursor.getString(4).startsWith("legacy:location_observation:") shouldBe true
			cursor.getString(5).startsWith("legacy:location_observation_event:") shouldBe true
			cursor.getString(6).startsWith("legacy:location_observation_callback:") shouldBe true
			cursor.getString(7) shouldBe "legacy:unknown"
			cursor.getLong(8) shouldBe 0L
			cursor.moveToNext() shouldBe false
		}

		raw.query(
			"""
			SELECT session_id, signal_json, created_at, signal_id, envelope_version,
				payload_checksum, claim_token, claim_expires_at, delivery_attempt_count,
				captured_epoch, acquired_at_ms
			FROM pending_signal WHERE id = 9001
			""".trimIndent(),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 44L
			cursor.getString(1) shouldBe "{\"edge\":true}"
			cursor.getLong(2) shouldBe 1_700_100_000_500L
			cursor.getString(3) shouldBe "legacy:pending_signal:9001"
			cursor.getInt(4) shouldBe 0
			cursor.isNull(5) shouldBe true
			cursor.isNull(6) shouldBe true
			cursor.isNull(7) shouldBe true
			cursor.getInt(8) shouldBe 0
			cursor.getLong(9) shouldBe 0L
			cursor.getLong(10) shouldBe 0L
		}

		shouldThrow<SQLiteConstraintException> {
			raw.execSQL(
				"""
				INSERT INTO pending_signal(signal_id, session_id, signal_json, created_at)
				VALUES ('legacy:pending_signal:9001', 45, '{}', 1700100000600)
				""".trimIndent(),
			)
		}
	}

	@Test
	fun `released v26 semantics are intentionally transformed and final indexes are present`() {
		val name = prepareReleasedV26(::seedV26EdgeRows)

		val raw = openCurrent(name).openHelper.writableDatabase

		columns(raw, "achievement_progress") shouldBe listOf(
			"metric_key",
			"last_tier_index",
			"last_value",
			"updated_at",
		)
		count(raw, "achievement_progress") shouldBe 0
		listTables(raw) shouldContain "xp_ledger"
		listTables(raw) shouldContain "player_profile"
		listTables(raw) shouldContain "minigame_score"
		indexNames(raw, "location_sample") shouldContain "idx_location_sample_time_id"
		indexNames(raw, "location_observation") shouldContain "idx_location_observation_source_event"
		indexNames(raw, "step_interval") shouldContain "idx_step_interval_end_time"
		indexColumns(raw, "idx_step_interval_end_time") shouldBe listOf("end_time_ms")
		indexNames(raw, "cell_sample") shouldContain "idx_cell_sample_time_id"
		indexColumns(raw, "idx_cell_sample_time_id") shouldBe listOf("time_ms", "id")
		indexNames(raw, "cell_sample") shouldContain "idx_cell_sample_identity"
		indexColumns(raw, "idx_cell_sample_identity") shouldBe listOf("mcc", "mnc", "cell_id")
		indexNames(raw, "wifi_observation") shouldContain "idx_wifi_obs_time_id"
		indexColumns(raw, "idx_wifi_obs_time_id") shouldBe listOf("time_ms", "id")
		indexNames(raw, "wifi_observation") shouldContain "idx_wifi_obs_bssid_time"
		indexColumns(raw, "idx_wifi_obs_bssid_time") shouldBe listOf("bssid", "time_ms")
		indexNames(raw, "pending_signal") shouldContain "idx_pending_signal_claimable"
		indexNames(raw, "pending_signal") shouldContain "idx_pending_signal_signal_id"
		indexNames(raw, "minigame_score") shouldContain "idx_minigame_score_played_at"
		raw.execSQL(
			"""
			INSERT INTO osm_import(
				display_name, file_uri, imported_at, way_count, node_count,
				min_lat_e7, max_lat_e7, min_lon_e7, max_lon_e7
			) VALUES ('edge.osm.pbf', 'content://edge', 1700100010000, 0, 0, 1, 2, 3, 4)
			""".trimIndent(),
		)
		raw.query(
			"SELECT status, cell_index_built, way_bbox_encoding_version, published_revision FROM osm_import",
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getString(0) shouldBe "READY"
			cursor.getInt(1) shouldBe 0
			cursor.getInt(2) shouldBe 0
			cursor.getLong(3) shouldBe 0L
		}
	}

	@Test
	fun `migration reconciles released v26 cursor and index drift without losing progress`() {
		val name = prepareReleasedV26 { db ->
			db.execSQL("DROP INDEX IF EXISTS index_domain_event_timestamp_ms_id")
			db.execSQL("CREATE INDEX index_domain_event_timestamp_ms ON domain_event(timestamp_ms)")
			db.execSQL("ALTER TABLE domain_event_cursor RENAME TO domain_event_cursor_v26")
			db.execSQL(
				"""
				CREATE TABLE domain_event_cursor (
					consumer_id TEXT PRIMARY KEY NOT NULL,
					last_processed_ms INTEGER NOT NULL
				)
				""".trimIndent(),
			)
			db.execSQL(
				"""
				INSERT INTO domain_event_cursor(consumer_id, last_processed_ms)
				VALUES ('achievement-processor', 1700000123456)
				""".trimIndent(),
			)
			db.execSQL("DROP TABLE domain_event_cursor_v26")
		}

		val raw = openCurrent(name).openHelper.writableDatabase

		val domainIndexes = indexNames(raw, "domain_event")
		domainIndexes shouldNotContain "index_domain_event_timestamp_ms"
		domainIndexes shouldContain "index_domain_event_timestamp_ms_id"
		indexColumns(raw, "index_domain_event_timestamp_ms_id") shouldBe listOf("timestamp_ms", "id")
		indexNames(raw, "session_segment") shouldContain "idx_session_segment_end_time_ms"
		indexNames(raw, "session_segment") shouldContain "idx_session_segment_primary_activity"
		raw.query(
			"SELECT last_processed_ms, last_processed_id FROM domain_event_cursor WHERE consumer_id = ?",
			arrayOf("achievement-processor"),
		).use { cursor ->
			cursor.moveToFirst() shouldBe true
			cursor.getLong(0) shouldBe 1_700_000_123_456L
			cursor.getLong(1) shouldBe 0L
		}
	}

	@Test
	fun `late schema validation failure rolls the entire migration back to v26`() {
		val name = prepareReleasedV26 { db ->
			db.execSQL("CREATE TABLE import_job_receipt (broken INTEGER NOT NULL)")
		}
		val room = buildCurrent(name)

		shouldThrow<SQLiteException> { room.openHelper.writableDatabase }
		room.close()

		openAtVersion(name, RELEASED_DATABASE_VERSION).use { raw ->
			raw.version shouldBe RELEASED_DATABASE_VERSION
			listTables(raw) shouldNotContain "xp_ledger"
			listTables(raw) shouldContain "import_job_receipt"
			columns(raw, "import_job_receipt") shouldBe listOf("broken")
		}
	}

	private fun seedV26EdgeRows(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			INSERT INTO location_sample(
				id, time_ms, elapsed_realtime_nanos, lat_e7, lon_e7,
				alt_m, raw_gps_alt_m, h_acc_m, v_acc_m, speed_mps,
				speed_accuracy_mps, provider, quality, motion_state, policy,
				bucket_id, created_at
			) VALUES (
				9001, 1700100000000, 123456789, NULL, NULL,
				321.25, NULL, NULL, 4.5, NULL,
				NULL, 'gps', 'ACCEPTED', NULL, NULL,
				NULL, 1700100000100
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO pending_signal(id, session_id, signal_json, created_at)
			VALUES (9001, 44, '{"edge":true}', 1700100000500)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO achievement_progress(
				id, achievement_id, current_value, target_value, tier,
				unlocked_at, updated_at, notified_at
			) VALUES (9001, 'retired-contract', 7, 10, NULL, NULL, 1700100000700, NULL)
			""".trimIndent(),
		)
	}

	private fun prepareReleasedV26(seed: (SupportSQLiteDatabase) -> Unit = {}): String {
		val name = copyFixture()
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(name)
				.callback(object : SupportSQLiteOpenHelper.Callback(RELEASED_DATABASE_VERSION) {
					override fun onCreate(db: SupportSQLiteDatabase) =
						error("Release fixture was not copied")

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						oldVersion shouldBe FIXTURE_DATABASE_VERSION
						newVersion shouldBe RELEASED_DATABASE_VERSION
						var expectedStart = oldVersion
						migrationsToV26.forEach { migration ->
							migration.startVersion shouldBe expectedStart
							migration.migrate(db)
							expectedStart = migration.endVersion
						}
						expectedStart shouldBe newVersion
					}
				})
				.build(),
		)
		try {
			val raw = helper.writableDatabase
			raw.version shouldBe RELEASED_DATABASE_VERSION
			seed(raw)
		} finally {
			helper.close()
		}
		return name
	}

	private fun openCurrent(name: String): AppDatabase = buildCurrent(name).also { database ->
		roomDatabases += database
		database.openHelper.writableDatabase
	}

	private fun buildCurrent(name: String): AppDatabase =
		Room.databaseBuilder(context, AppDatabase::class.java, name)
			.addMigrations(*AppDatabase.migrations)
			.allowMainThreadQueries()
			.build()

	private fun openAtVersion(name: String, version: Int): SupportSQLiteDatabase {
		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(name)
				.callback(object : SupportSQLiteOpenHelper.Callback(version) {
					override fun onCreate(db: SupportSQLiteDatabase) = error("Database disappeared")
					override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
						error("Unexpected upgrade $oldVersion->$newVersion")
				})
				.build(),
		)
		return helper.writableDatabase
	}

	private fun copyFixture(): String {
		val name = "v26-to-v27-${databaseNames.size}.db"
		context.deleteDatabase(name)
		databaseNames += name
		val target = context.getDatabasePath(name)
		target.parentFile?.mkdirs()
		checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE_RESOURCE)) {
			"Missing release fixture: $FIXTURE_RESOURCE"
		}.use { input -> target.outputStream().use(input::copyTo) }
		return name
	}

	private fun deleteAllApplicationRows(db: SupportSQLiteDatabase) {
		db.execSQL("PRAGMA foreign_keys = OFF")
		listTables(db).forEach { table -> db.execSQL("DELETE FROM `$table`") }
		db.execSQL("PRAGMA foreign_keys = ON")
	}

	private fun listTables(db: SupportSQLiteDatabase): Set<String> =
		db.query(
			"""
			SELECT name FROM sqlite_master
			WHERE type = 'table'
				AND name NOT LIKE 'sqlite_%'
				AND name NOT LIKE 'android_%'
				AND name != 'room_master_table'
			""".trimIndent(),
		).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

	private fun count(db: SupportSQLiteDatabase, table: String): Int =
		db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
			cursor.moveToFirst()
			cursor.getInt(0)
		}

	private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
		db.query("PRAGMA table_info('$table')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> =
		db.query("PRAGMA index_list('$table')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private fun indexColumns(db: SupportSQLiteDatabase, index: String): List<String> =
		db.query("PRAGMA index_info('$index')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private companion object {
		const val FIXTURE_RESOURCE = "baseline/2024.1/main_database.db"
		const val FIXTURE_DATABASE_VERSION = 10
		const val RELEASED_DATABASE_VERSION = 26

		val migrationsToV26: List<Migration> = AppDatabase.migrations.filter {
			it.startVersion >= FIXTURE_DATABASE_VERSION && it.endVersion <= RELEASED_DATABASE_VERSION
		}
	}
}
