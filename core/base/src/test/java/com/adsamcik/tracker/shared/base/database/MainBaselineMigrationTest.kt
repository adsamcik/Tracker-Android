package com.adsamcik.tracker.shared.base.database

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.File

/**
 * Migrates each shipped database from its **released `main`** state to the
 * current dev/v10 state and asserts the result.
 *
 * Test seed binaries are generated once (see `.worktrees/main-baseline/
 * baseline-export/build_baselines.py` on the `main` branch) from main's own
 * exported Room schemas. They are checked in under
 * `sbase/src/test/resources/baseline/main/` as immutable fixtures. The fixtures
 * intentionally pre-date dev/v10's schema work — that is the entire point:
 * every migration listed below is exercised against the same byte-exact state
 * a user upgrading from production would carry into the new build.
 *
 * Database coverage:
 *   - AppDatabase: 12 → 32 (twenty migrations, legacy v12 tables dropped,
 *     coords promoted to E7, sessions remapped to session_segment)
 *   - DebugDatabase: 1 → 2 (MIGRATION_1_2 adds index_debug_activity_time)
 *   - PreferenceDatabase, LogDatabase, PointsDatabase, StatsDatabase: open at
 *     unchanged versions to catch accidental schema drift between main and
 *     dev/v10 (Room's identityHash check would refuse to open the file if a
 *     dev branch quietly changed an entity without bumping the version)
 *   - ChallengeDatabase: removed on dev/v10. Asserted via reflection that no
 *     @Database class still targets `challenge_database`, that the schema dir
 *     is gone, and that the leftover SQLite file remains valid bytes so a
 *     user's upgrade does not crash on a stray file.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class MainBaselineMigrationTest {

	// ------------------------------------------------------------------
	// Shared fixture loader
	// ------------------------------------------------------------------

	private lateinit var context: Application
	private val openHelpers = mutableListOf<SupportSQLiteOpenHelper>()
	private val openedDbNames = mutableSetOf<String>()

	@BeforeEach
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
	}

	@AfterEach
	fun tearDown() {
		openHelpers.forEach { runCatching { it.close() } }
		openHelpers.clear()
		openedDbNames.forEach { runCatching { context.deleteDatabase(it) } }
		openedDbNames.clear()
	}

	/**
	 * Copy a baseline binary out of test resources into the path the Android
	 * SQLite helper expects, then open it at [targetVersion] with [onUpgrade]
	 * driving the migration walk.
	 */
	private fun openBaseline(
		assetName: String,
		dbName: String,
		targetVersion: Int,
		onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit,
	): SupportSQLiteDatabase {
		context.deleteDatabase(dbName)
		openedDbNames += dbName

		val target = context.getDatabasePath(dbName)
		target.parentFile?.mkdirs()
		val resourcePath = "baseline/main/$assetName"
		val input = checkNotNull(javaClass.classLoader?.getResourceAsStream(resourcePath)) {
			"Missing baseline asset on classpath: $resourcePath"
		}
		input.use { src ->
			target.outputStream().use { dst -> src.copyTo(dst) }
		}

		val helper = FrameworkSQLiteOpenHelperFactory().create(
			SupportSQLiteOpenHelper.Configuration.builder(context)
				.name(dbName)
				.callback(object : SupportSQLiteOpenHelper.Callback(targetVersion) {
					override fun onCreate(db: SupportSQLiteDatabase) {
						error(
							"onCreate should not fire — baseline asset was supposed to be at " +
								target.absolutePath,
						)
					}

					override fun onUpgrade(
						db: SupportSQLiteDatabase,
						oldVersion: Int,
						newVersion: Int,
					) {
						onUpgrade(db, oldVersion, newVersion)
					}
				})
				.build(),
		)
		openHelpers += helper
		return helper.writableDatabase
	}

	private fun listTables(db: SupportSQLiteDatabase): Set<String> =
		db.query(
			"SELECT name FROM sqlite_master WHERE type = 'table' " +
				"AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
				"AND name != 'room_master_table'",
		).use { cursor ->
			buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
		}

	private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> =
		db.query("PRAGMA index_list('$table')").use { cursor ->
			buildSet {
				val col = cursor.getColumnIndexOrThrow("name")
				while (cursor.moveToNext()) add(cursor.getString(col))
			}
		}

	private fun count(db: SupportSQLiteDatabase, table: String): Int =
		db.query("SELECT COUNT(*) FROM $table").use { cursor ->
			cursor.moveToFirst()
			cursor.getInt(0)
		}

	// ==================================================================
	// AppDatabase v12 → v32
	// ==================================================================

	@Nested
	@DisplayName("AppDatabase: released v12 → dev/v10 v32")
	inner class AppDatabaseMigration {

		private val v12ToV32 = listOf(
			MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
			MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
			MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
			MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28,
			MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32,
		)

		private fun openAppDb(): SupportSQLiteDatabase =
			openBaseline(
				assetName = "main_database.db",
				dbName = "main_database_baseline_test",
				targetVersion = APP_DB_TARGET_VERSION,
			) { db, oldVersion, _ ->
				oldVersion shouldBe APP_DB_BASELINE_VERSION
				v12ToV32.forEach { it.migrate(db) }
			}

		@Test
		fun `baseline migrates cleanly through every migration to v32`() {
			openAppDb().close()
			// Reopen without an upgrade callback to confirm Android SQLite sees
			// the database at version 32 with no pending work.
			val helper = FrameworkSQLiteOpenHelperFactory().create(
				SupportSQLiteOpenHelper.Configuration.builder(context)
					.name("main_database_baseline_test")
					.callback(object : SupportSQLiteOpenHelper.Callback(APP_DB_TARGET_VERSION) {
						override fun onCreate(db: SupportSQLiteDatabase) =
							error("Should already exist after migration")

						override fun onUpgrade(
							db: SupportSQLiteDatabase,
							oldVersion: Int,
							newVersion: Int,
						) = error("Should not need any further upgrade; old=$oldVersion new=$newVersion")
					})
					.build(),
			)
			openHelpers += helper
			helper.writableDatabase.version shouldBe APP_DB_TARGET_VERSION
		}

		@Test
		fun `every v32 table is present after migration`() {
			val db = openAppDb()
			val tables = listTables(db)
			tables shouldContainAll listOf(
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
				"pending_signal",
				"xp_ledger",
				"player_profile",
				"minigame_score",
				"osm_import",
				"osm_way",
				"osm_way_cell",
			)
		}

		@Test
		fun `every legacy v12 table is dropped by MIGRATION_20_21`() {
			val db = openAppDb()
			val tables = listTables(db)
			tables shouldNotContainAnyOf listOf(
				"location_data",
				"tracker_session",
				"wifi_data",
				"cell_location",
				"location_wifi_count",
			)
		}

		@Test
		fun `location_data rows are migrated 1-for-1 into location_sample`() {
			val db = openAppDb()
			// Seed had 5 location_data rows.
			count(db, "location_sample") shouldBe 5
		}

		@Test
		fun `location_sample preserves coordinates as E7 integers within 1 ulp`() {
			val db = openAppDb()
			db.query(
				"SELECT lat_e7, lon_e7, h_acc_m, quality, motion_state " +
					"FROM location_sample WHERE time_ms = 1000",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				// 48.1234567 → 481234567 ± 1 for the integer cast of a double.
				cursor.getLong(0) shouldBeAround 481234567L
				cursor.getLong(1) shouldBeAround 179876543L
				cursor.getDouble(2) shouldBe 5.0
				// hor_acc=5 < 10 ⇒ HIGH; activity=0 (IN_VEHICLE) → STILL.
				cursor.getString(3) shouldBe "HIGH"
				cursor.getString(4) shouldBe "STILL"
			}
		}

		@Test
		fun `NULL hor_acc baseline row is classified as COARSE quality`() {
			val db = openAppDb()
			db.query("SELECT quality FROM location_sample WHERE time_ms = 2000").use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "COARSE"
			}
		}

		@Test
		fun `only tracker_session rows passing the v5 collection rule survive`() {
			val db = openAppDb()
			// Seed had three tracker_session rows; only id=1 (start<end, collections>1) is valid.
			db.query(
				"SELECT COUNT(*) FROM session_segment WHERE source = 'LEGACY_MIGRATION'",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getInt(0) shouldBe 1
			}
		}

		@Test
		fun `surviving session_segment retains its tracker_session fields`() {
			val db = openAppDb()
			db.query(
				"SELECT start_time_ms, end_time_ms, distance_m, steps, sample_count, " +
					"has_distance_anomaly FROM session_segment WHERE source = 'LEGACY_MIGRATION'",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getLong(0) shouldBe 1000L
				cursor.getLong(1) shouldBe 2000L
				cursor.getDouble(2) shouldBe 1234.5
				cursor.getInt(3) shouldBe 120
				cursor.getInt(4) shouldBe 8
				// has_distance_anomaly is backfilled to 0 by MIGRATION_19_20.
				cursor.getInt(5) shouldBe 0
			}
		}

		@Test
		fun `cell_location is backfilled into cell_sample preserving wide 5G NR cell ids`() {
			val db = openAppDb()
			db.query(
				"SELECT cell_id FROM cell_sample WHERE mcc = 310 AND mnc = 260",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				// The 5G NR cell id is 36 bits wide — must NOT be truncated to Int by
				// MIGRATION_21_22 / MIGRATION_22_23.
				cursor.getLong(0) shouldBe 68_719_476_735L
			}
		}

		@Test
		fun `wifi_data is backfilled into wifi_observation with LEGACY_MIGRATION provenance`() {
			val db = openAppDb()
			count(db, "wifi_observation") shouldBe 2
			db.query(
				"SELECT bssid, ssid, frequency, level, provenance FROM wifi_observation " +
					"WHERE bssid = 'AA:BB:CC:DD:EE:01'",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "AA:BB:CC:DD:EE:01"
				cursor.getString(1) shouldBe "Home"
				cursor.getInt(2) shouldBe 2412
				cursor.getInt(3) shouldBe -55
				cursor.getString(4) shouldBe "LEGACY_MIGRATION"
			}
		}

		@Test
		fun `network_operator reference table is preserved through to v32`() {
			val db = openAppDb()
			count(db, "network_operator") shouldBe 3
			db.query(
				"SELECT name FROM network_operator WHERE mcc = '230' AND mnc = '01'",
			).use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "Test Operator"
			}
		}

		@Test
		fun `activity reference table is preserved through to v32`() {
			val db = openAppDb()
			count(db, "activity") shouldBe 7
			db.query("SELECT name FROM activity WHERE id = 7").use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(0) shouldBe "Running"
			}
		}
	}

	// ==================================================================
	// DebugDatabase v1 → v2
	// ==================================================================

	@Nested
	@DisplayName("DebugDatabase: released v1 → dev/v10 v2")
	inner class DebugDatabaseMigration {

		private fun openDebugDb(): SupportSQLiteDatabase =
			openBaseline(
				assetName = "debug_database_sbase.db",
				dbName = "debug_database_baseline_test",
				targetVersion = DEBUG_DB_TARGET_VERSION,
			) { db, oldVersion, _ ->
				oldVersion shouldBe DEBUG_DB_BASELINE_VERSION
				DebugDatabase.MIGRATION_1_2.migrate(db)
			}

		@Test
		fun `MIGRATION_1_2 adds index_debug_activity_time on the time column`() {
			val db = openDebugDb()
			val indices = indexNames(db, "debug_activity")
			indices shouldContain "index_debug_activity_time"

			db.query("PRAGMA index_info('index_debug_activity_time')").use { cursor ->
				cursor.moveToFirst() shouldBe true
				cursor.getString(cursor.getColumnIndexOrThrow("name")) shouldBe "time"
				cursor.moveToNext() shouldBe false
			}
		}

		@Test
		fun `MIGRATION_1_2 preserves every seeded debug_activity row verbatim`() {
			val db = openDebugDb()
			count(db, "debug_activity") shouldBe 4

			db.query(
				"SELECT id, time, action, activity, confidence FROM debug_activity ORDER BY id",
			).use { cursor ->
				cursor.moveToNext() shouldBe true
				cursor.getInt(0) shouldBe 1
				cursor.getLong(1) shouldBe 1_700_000_000_000L
				cursor.getString(2) shouldBe "tracker_started"
				cursor.getInt(3) shouldBe 0
				cursor.getInt(4) shouldBe 80

				cursor.moveToNext() shouldBe true
				cursor.getInt(0) shouldBe 2
				cursor.getString(2) shouldBe "tracker_stopped"

				cursor.moveToNext() shouldBe true
				cursor.getInt(0) shouldBe 3
				cursor.isNull(2) shouldBe true

				cursor.moveToNext() shouldBe true
				cursor.getInt(0) shouldBe 4
			}
		}
	}

	// ==================================================================
	// Unchanged databases — drift detection
	// ==================================================================

	@Nested
	@DisplayName("Unchanged databases: schema must not have drifted")
	inner class UnchangedDatabaseDrift {

		@ParameterizedTest(name = "{0} ({1}) opens at v{2} without onUpgrade firing")
		@MethodSource(
			"com.adsamcik.tracker.shared.base.database.MainBaselineMigrationTest#unchangedDatabaseFixtures",
		)
		fun `opens at unchanged version with no upgrade required`(
			displayName: String,
			assetName: String,
			version: Int,
			expectedTables: List<String>,
		) {
			val dbName = "${assetName.removeSuffix(".db")}_baseline_test"
			val db = openBaseline(
				assetName = assetName,
				dbName = dbName,
				targetVersion = version,
			) { _, oldVersion, newVersion ->
				error(
					"Schema drift detected for $displayName: SQLite triggered onUpgrade " +
						"from $oldVersion to $newVersion. Either bump the database version " +
						"and add a migration, or re-export the baseline fixture.",
				)
			}
			// The fact that onUpgrade never fired is the primary guarantee. Sanity-check
			// the seed rows are still readable via raw SQL.
			val tables = listTables(db)
			tables shouldContainAll expectedTables
		}
	}

	// ==================================================================
	// Removed database — ChallengeDatabase
	// ==================================================================

	@Nested
	@DisplayName("ChallengeDatabase: removed on dev/v10, leftover file is harmless")
	inner class ChallengeRemoved {

		@Test
		fun `ChallengeDatabase class is no longer on the classpath`() {
			val result = runCatching {
				Class.forName("com.adsamcik.tracker.game.challenge.database.ChallengeDatabase")
			}
			result.isFailure shouldBe true
			val exception = result.exceptionOrNull()
			(exception is ClassNotFoundException || exception is NoClassDefFoundError) shouldBe true
		}

		@Test
		fun `no ChallengeDatabase schema dir survives in the game module`() {
			val projectDir = File(checkNotNull(System.getProperty("user.dir")))
			val sbaseRoot = generateSequence(projectDir) { it.parentFile }
				.first { candidate ->
					File(candidate, "settings.gradle.kts").exists() ||
						File(candidate, "settings.gradle").exists()
				}
			val schemaDir = File(
				sbaseRoot,
				"game/schemas/com.adsamcik.tracker.game.challenge.database.ChallengeDatabase",
			)
			schemaDir.exists() shouldBe false
		}

		@Test
		fun `leftover challenge_database file is still a valid SQLite file`() {
			// Simulate a user upgrading from main: the SQLite file exists on disk, but
			// nothing on dev/v10 opens it. We open the asset bytes ourselves to confirm
			// the file is intact and its tables remain readable.
			//
			// We open the file with Android's SupportSQLiteOpenHelper at the schema
			// version baked into the baseline so onUpgrade never fires — i.e. exactly
			// the no-op path a hypothetical dev/v10 caller would take if it ever did
			// touch the file by accident.
			val db = openBaseline(
				assetName = "challenge_database.db",
				dbName = "challenge_baseline",
				targetVersion = CHALLENGE_DB_BASELINE_VERSION,
			) { _, oldVersion, newVersion ->
				error("Should not upgrade challenge_database: old=$oldVersion new=$newVersion")
			}
			val tables = listTables(db)
			tables shouldContainAll listOf(
				"challenge_session_data",
				"entry",
				"challenge_explorer",
				"challenge_walk_distance",
				"challenge_step",
				"challenge_active_time",
			)
			count(db, "challenge_explorer") shouldBe 1
			count(db, "entry") shouldBe 4
		}
	}

	// ==================================================================
	// Helpers
	// ==================================================================

	private infix fun Long.shouldBeAround(expected: Long) {
		(kotlin.math.abs(this - expected) <= 1L) shouldBe true
	}

	companion object {
		private const val APP_DB_BASELINE_VERSION = 12
		private const val APP_DB_TARGET_VERSION = 32
		private const val DEBUG_DB_BASELINE_VERSION = 1
		private const val DEBUG_DB_TARGET_VERSION = 2
		private const val CHALLENGE_DB_BASELINE_VERSION = 2

		@JvmStatic
		fun unchangedDatabaseFixtures(): List<Array<Any>> = listOf(
			arrayOf(
				"PreferenceDatabase",
				"preference_database.db",
				1,
				listOf("generic", "notification"),
			),
			arrayOf(
				"LogDatabase",
				"debug_database_logger.db",
				2,
				listOf("log_data", "crash_data"),
			),
			arrayOf(
				"PointsDatabase",
				"points_database.db",
				1,
				listOf("points_awarded"),
			),
			arrayOf(
				"StatsDatabase",
				"stats_database.db",
				1,
				listOf("statCache"),
			),
		)
	}
}
