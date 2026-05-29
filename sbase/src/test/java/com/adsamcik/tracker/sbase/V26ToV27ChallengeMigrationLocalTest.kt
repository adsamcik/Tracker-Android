package com.adsamcik.tracker.sbase

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFold
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFoldMarker
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFoldResult
import com.adsamcik.tracker.shared.base.database.MIGRATION_26_27
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.File

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class V26ToV27ChallengeMigrationLocalTest {
	private lateinit var application: Application
	private lateinit var dbFile: File
	private lateinit var openHelper: SupportSQLiteOpenHelper
	private lateinit var db: SupportSQLiteDatabase
	private var appDatabase: AppDatabase? = null

	@BeforeEach
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		deleteLegacyChallengeFiles()
		dbFile = File(application.cacheDir, "v26_to_v27_challenge_test.db")
		if (dbFile.exists()) dbFile.delete()

		val configuration = SupportSQLiteOpenHelper.Configuration.builder(application)
			.name(dbFile.absolutePath)
			.callback(object : SupportSQLiteOpenHelper.Callback(26) {
				override fun onCreate(db: SupportSQLiteDatabase) = applySchemaFromJson(db, V26_SCHEMA)
				override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
			})
			.build()
		openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
		db = openHelper.writableDatabase
		assertEquals(26, db.version)
	}

	@AfterEach
	fun tearDown() {
		appDatabase?.close()
		if (::openHelper.isInitialized) openHelper.close()
		if (::dbFile.isInitialized && dbFile.exists()) dbFile.delete()
		deleteLegacyChallengeFiles()
	}

	@Test
	fun migrate26To27_createsChallengeTables() {
		MIGRATION_26_27.migrate(db)

		val expectedTables = listOf(
			"challenge",
			"challenge_history",
			"challenge_streak",
			"challenge_personal_record",
			"xp_ledger",
			"player_profile",
			"minigame_score",
		)
		expectedTables.forEach { table -> assertTrue(tableExists(table), "Expected table $table") }

		assertEquals(
			setOf(
				"id", "type", "start_time", "end_time", "difficulty", "required_value",
				"current_value", "is_completed", "extra_json"
			),
			columnNames("challenge"),
		)
		assertEquals(
			setOf(
				"id", "challenge_type", "difficulty", "start_time", "end_time", "outcome",
				"completed_at", "progress_value", "target_value", "medal", "xp_awarded",
				"original_challenge_id"
			),
			columnNames("challenge_history"),
		)
		assertEquals(
			setOf("id", "challenge_type", "metric", "value", "history_id", "achieved_at"),
			columnNames("challenge_personal_record"),
		)
		assertTrue(indexNames("challenge").contains("index_challenge_is_completed_end_time"))
		assertTrue(indexNames("challenge_history").contains("index_challenge_history_completed_at"))
		assertTrue(
			indexNames("challenge_personal_record")
				.contains("index_challenge_personal_record_challenge_type_metric")
		)
		assertTrue(indexNames("xp_ledger").contains("index_xp_ledger_source_source_id"))
		assertTrue(foreignKeyTargets("challenge_personal_record").contains("challenge_history"))
	}

	@Test
	fun migrate26To27_enforcesPersonalRecordUniqueness() {
		MIGRATION_26_27.migrate(db)
		db.execSQL(
			"INSERT INTO challenge_personal_record (challenge_type, metric, value, history_id, achieved_at) " +
				"VALUES ('Step', 'steps', 100.0, NULL, 1)"
		)

		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO challenge_personal_record (challenge_type, metric, value, history_id, achieved_at) " +
					"VALUES ('Step', 'steps', 200.0, NULL, 2)"
			)
		}
	}

	@Test
	fun migrate26To27_enforcesPersonalRecordForeignKey() {
		MIGRATION_26_27.migrate(db)
		db.setForeignKeyConstraintsEnabled(true)

		assertThrows(SQLiteConstraintException::class.java) {
			db.execSQL(
				"INSERT INTO challenge_personal_record (challenge_type, metric, value, history_id, achieved_at) " +
					"VALUES ('Step', 'steps', 100.0, 999, 1)"
			)
		}
	}

	@Test
	fun fold_returnsNoLegacyDatabaseWhenMissing() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		val marker = FakeFoldMarker()

		val result = ChallengeDatabaseFold(application, appDatabase!!, marker).foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE, result)
		assertFalse(marker.complete)
	}

	@Test
	fun fold_returnsAlreadyCompleteWithoutTouchingLegacyFile() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		val marker = FakeFoldMarker(complete = true)
		seedLegacyChallengeDatabase(includeEmptyLegacyTables = true)

		val result = ChallengeDatabaseFold(application, appDatabase!!, marker).foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.ALREADY_COMPLETE, result)
		assertTrue(application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).exists())
		assertFalse(legacyBackupFile().exists())
	}

	@Test
	fun fold_copiesPreV8LegacyTypedTables() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		val marker = FakeFoldMarker()
		seedPreV8LegacyChallengeDatabase()

		val fold = ChallengeDatabaseFold(application, appDatabase!!, marker)
		val result = fold.foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.COPIED, result, fold.lastFailure?.stackTraceToString())
		assertTrue(marker.complete)
		assertFalse(marker.unrecoverable)
		assertFalse(application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).exists())
		assertTrue(legacyBackupFile().exists())

		val rows = queryRows(
			"SELECT id, type, difficulty, required_value, current_value, is_completed FROM challenge ORDER BY type"
		)
		assertEquals(4, rows.size)
		val byType = rows.associateBy { it.getString("type") }
		assertEquals(5.0, byType.getValue("Explorer").getDouble("required_value"), 0.0)
		assertEquals(5.0, byType.getValue("Explorer").getDouble("current_value"), 0.0)
		assertEquals(1L, byType.getValue("Explorer").getLong("is_completed"))
		assertEquals(1000.0, byType.getValue("Step").getDouble("required_value"), 0.0)
		assertEquals(250.0, byType.getValue("Step").getDouble("current_value"), 0.0)
		assertEquals(5000.0, byType.getValue("WalkDistance").getDouble("required_value"), 0.0)
		assertEquals(1200.0, byType.getValue("WalkDistance").getDouble("current_value"), 0.0)
		assertEquals(60.0, byType.getValue("ActiveTime").getDouble("required_value"), 0.0)
		assertEquals(30.0, byType.getValue("ActiveTime").getDouble("current_value"), 0.0)
		assertEquals("EASY", byType.getValue("Explorer").getString("difficulty"))
		assertEquals("MEDIUM", byType.getValue("Step").getString("difficulty"))

		val history = queryRows(
			"SELECT challenge_type, outcome, completed_at, progress_value, target_value, original_challenge_id FROM challenge_history ORDER BY challenge_type"
		)
		assertEquals(2, history.size)
		val historyByType = history.associateBy { it.getString("challenge_type") }
		assertEquals("EXPIRED", historyByType.getValue("ActiveTime").getString("outcome"))
		assertEquals("COMPLETED", historyByType.getValue("Explorer").getString("outcome"))
		assertEquals(byType.getValue("Explorer").getLong("id"), historyByType.getValue("Explorer").getLong("original_challenge_id"))

		SQLiteDatabase.openDatabase(legacyBackupFile().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
			LEGACY_TABLES.forEach { table ->
				assertFalse(backup.tableExists(table), "Expected legacy table $table to be dropped before backup")
			}
		}
	}

	@Test
	fun fold_copiesPreV8RowsEvenWhenUnifiedChallengeTableHasRows() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		val marker = FakeFoldMarker()
		seedMixedUnifiedAndPreV8LegacyChallengeDatabase()

		val fold = ChallengeDatabaseFold(application, appDatabase!!, marker)
		val result = fold.foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.COPIED, result, fold.lastFailure?.stackTraceToString())
		val byType = queryRows(
			"SELECT type, required_value, current_value FROM challenge ORDER BY type"
		).associateBy { it.getString("type") }
		assertEquals(setOf("Step", "WalkDistance"), byType.keys)
		assertEquals(123.0, byType.getValue("Step").getDouble("current_value"), 0.0)
		assertEquals(9000.0, byType.getValue("WalkDistance").getDouble("required_value"), 0.0)
		assertTrue(marker.complete)
	}

	@Test
	fun migrate26To27_copiesExistingChallengeData() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		val marker = FakeFoldMarker()
		seedLegacyChallengeDatabase(includeEmptyLegacyTables = true)

		val fold = ChallengeDatabaseFold(application, appDatabase!!, marker)
		val result = fold.foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.COPIED, result, fold.lastFailure?.stackTraceToString())
		assertTrue(marker.complete)
		assertFalse(application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).exists())
		assertTrue(legacyBackupFile().exists())

		val challenge = querySingleRow("SELECT id, type, current_value FROM challenge")
		val newChallengeId = challenge.getLong("id")
		assertNotEquals(5L, newChallengeId)
		assertEquals("Step", challenge.getString("type"))
		assertEquals(123.0, challenge.getDouble("current_value"), 0.0)

		val history = querySingleRow("SELECT id, original_challenge_id, outcome FROM challenge_history")
		val newHistoryId = history.getLong("id")
		assertNotEquals(7L, newHistoryId)
		assertEquals(newChallengeId, history.getLong("original_challenge_id"))
		assertEquals("COMPLETED", history.getString("outcome"))

		val xp = querySingleRow("SELECT amount, source, source_id FROM xp_ledger")
		assertEquals(50L, xp.getLong("amount"))
		assertEquals("CHALLENGE", xp.getString("source"))
		assertEquals(newChallengeId, xp.getLong("source_id"))

		val record = querySingleRow("SELECT challenge_type, metric, history_id FROM challenge_personal_record")
		assertEquals("Step", record.getString("challenge_type"))
		assertEquals("steps", record.getString("metric"))
		assertEquals(newHistoryId, record.getLong("history_id"))

		val profile = querySingleRow("SELECT total_xp, level FROM player_profile")
		assertEquals(500L, profile.getLong("total_xp"))
		assertEquals(4L, profile.getLong("level"))

		val score = querySingleRow("SELECT game_id, score, xp_awarded FROM minigame_score")
		assertEquals("outrun", score.getString("game_id"))
		assertEquals(42.5, score.getDouble("score"), 0.0)
		assertEquals(15L, score.getLong("xp_awarded"))

		assertEquals(0, foreignKeyCheckCount("challenge_personal_record"))
	}

	@Test
	fun migrate26To27_dropsLegacyChallengeTables() = runTest {
		appDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
			.allowMainThreadQueries()
			.build()
		seedLegacyChallengeDatabase(includeEmptyLegacyTables = true)

		val fold = ChallengeDatabaseFold(application, appDatabase!!, FakeFoldMarker())
		val result = fold.foldIfNeeded()

		assertEquals(ChallengeDatabaseFoldResult.COPIED, result, fold.lastFailure?.stackTraceToString())
		SQLiteDatabase.openDatabase(legacyBackupFile().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
			LEGACY_TABLES.forEach { table ->
				assertFalse(backup.tableExists(table), "Expected legacy table $table to be dropped before backup")
			}
		}
	}

	private fun applySchemaFromJson(db: SupportSQLiteDatabase, schemaFile: File) {
		require(schemaFile.exists()) { "Schema file not found at ${schemaFile.absolutePath}" }
		val root = JSONObject(schemaFile.readText())
		val database = root.getJSONObject("database")
		require(database.getInt("version") == 26) { "Expected schema v26" }
		val entities = database.getJSONArray("entities")
		for (i in 0 until entities.length()) {
			val entity = entities.getJSONObject(i)
			val tableName = entity.getString("tableName")
			db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
			val indices = entity.optJSONArray("indices") ?: continue
			for (j in 0 until indices.length()) {
				db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
			}
		}
	}

	private fun seedLegacyChallengeDatabase(includeEmptyLegacyTables: Boolean) {
		val file = application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME)
		file.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
			createLegacyActiveTables(legacy)
			if (includeEmptyLegacyTables) createLegacyDeadTables(legacy)
			legacy.execSQL(
				"""
				INSERT INTO challenge (id, type, start_time, end_time, difficulty, required_value,
				current_value, is_completed, extra_json)
				VALUES (5, 'Step', 100, 200, 'MEDIUM', 200.0, 123.0, 0, '{"source":"test"}')
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_history (id, challenge_type, difficulty, start_time, end_time,
				outcome, completed_at, progress_value, target_value, medal, xp_awarded,
				original_challenge_id)
				VALUES (7, 'Step', 'MEDIUM', 100, 200, 'COMPLETED', 190, 200.0, 200.0,
				'GOLD', 50, 5)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_streak (id, current_count, best_count, last_completion_time, freeze_count)
				VALUES (1, 2, 3, 190, 1)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_personal_record (id, challenge_type, metric, value, history_id, achieved_at)
				VALUES (9, 'Step', 'steps', 200.0, 7, 190)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO xp_ledger (id, amount, source, source_id, earned_at)
				VALUES (11, 50, 'CHALLENGE', 5, 190)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO player_profile (id, total_xp, level, xp_into_current_level, xp_for_next_level)
				VALUES (1, 500, 4, 20, 180)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO minigame_score (id, game_id, score, xp_awarded, played_at)
				VALUES (13, 'outrun', 42.5, 15, 210)
				""".trimIndent()
			)
		}
	}

	private fun seedPreV8LegacyChallengeDatabase() {
		val file = application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME)
		file.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
			createPreV8LegacyTables(legacy)
			legacy.execSQL(
				"""
				INSERT INTO challenge_session_data (id, challenge_processed)
				VALUES (99, 1)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO entry (id, type, start_time, end_time, difficulty) VALUES
				(1, 0, 100, 200, 1),
				(2, 1, 100, 4102444800000, 3),
				(3, 2, 100, 4102444800000, 2),
				(4, 3, 100, 200, 4)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_explorer (required_location_count, location_count, entry_id, completed)
				VALUES (5, 5, 1, 1)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_walk_distance (required_distance, distance, entry_id, completed)
				VALUES (5000.0, 1200.0, 2, 0)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_step (requiredStepCount, stepCount, entry_id, completed)
				VALUES (1000, 250, 3, 0)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_active_time (activeTimeInMinutes, requiredActiveTimeInMinutes, entry_id, completed)
				VALUES (30, 60, 4, 0)
				""".trimIndent()
			)
		}
	}

	private fun seedMixedUnifiedAndPreV8LegacyChallengeDatabase() {
		val file = application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME)
		file.parentFile?.mkdirs()
		SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
			createLegacyActiveTables(legacy)
			createPreV8LegacyTables(legacy)
			legacy.execSQL(
				"""
				INSERT INTO challenge (id, type, start_time, end_time, difficulty, required_value,
				current_value, is_completed, extra_json)
				VALUES (5, 'Step', 100, 4102444800000, 'MEDIUM', 200.0, 123.0, 0, NULL)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO entry (id, type, start_time, end_time, difficulty)
				VALUES (10, 1, 100, 4102444800000, 2)
				""".trimIndent()
			)
			legacy.execSQL(
				"""
				INSERT INTO challenge_walk_distance (required_distance, distance, entry_id, completed)
				VALUES (9000.0, 4500.0, 10, 0)
				""".trimIndent()
			)
		}
	}

	private fun createLegacyActiveTables(db: SQLiteDatabase) {
		db.execSQL("""
			CREATE TABLE challenge (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				type TEXT NOT NULL,
				start_time INTEGER NOT NULL,
				end_time INTEGER NOT NULL,
				difficulty TEXT NOT NULL,
				required_value REAL NOT NULL,
				current_value REAL NOT NULL,
				is_completed INTEGER NOT NULL,
				extra_json TEXT
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE challenge_history (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				challenge_type TEXT NOT NULL,
				difficulty TEXT NOT NULL,
				start_time INTEGER NOT NULL,
				end_time INTEGER NOT NULL,
				outcome TEXT NOT NULL,
				completed_at INTEGER,
				progress_value REAL NOT NULL,
				target_value REAL NOT NULL,
				medal TEXT,
				xp_awarded INTEGER NOT NULL,
				original_challenge_id INTEGER
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE challenge_streak (
				id INTEGER NOT NULL,
				current_count INTEGER NOT NULL,
				best_count INTEGER NOT NULL,
				last_completion_time INTEGER NOT NULL,
				freeze_count INTEGER NOT NULL,
				PRIMARY KEY(id)
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE challenge_personal_record (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				challenge_type TEXT NOT NULL,
				metric TEXT NOT NULL,
				value REAL NOT NULL,
				history_id INTEGER,
				achieved_at INTEGER NOT NULL
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE xp_ledger (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				amount INTEGER NOT NULL,
				source TEXT NOT NULL,
				source_id INTEGER,
				earned_at INTEGER NOT NULL
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE player_profile (
				id INTEGER NOT NULL,
				total_xp INTEGER NOT NULL,
				level INTEGER NOT NULL,
				xp_into_current_level INTEGER NOT NULL,
				xp_for_next_level INTEGER NOT NULL,
				PRIMARY KEY(id)
			)
		""".trimIndent())
		db.execSQL("""
			CREATE TABLE minigame_score (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				game_id TEXT NOT NULL,
				score REAL NOT NULL,
				xp_awarded INTEGER NOT NULL,
				played_at INTEGER NOT NULL
			)
		""".trimIndent())
	}

	private fun createLegacyDeadTables(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE challenge_session_data (id INTEGER NOT NULL, challenge_processed INTEGER NOT NULL, PRIMARY KEY(id))")
		db.execSQL("CREATE TABLE entry (type TEXT NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, difficulty TEXT NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
		db.execSQL("CREATE TABLE challenge_explorer (required_location_count INTEGER NOT NULL, location_count INTEGER NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
		db.execSQL("CREATE TABLE challenge_walk_distance (required_distance REAL NOT NULL, distance REAL NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
		db.execSQL("CREATE TABLE challenge_step (requiredStepCount INTEGER NOT NULL, stepCount INTEGER NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
		db.execSQL("CREATE TABLE challenge_active_time (activeTimeInMinutes INTEGER NOT NULL, requiredActiveTimeInMinutes INTEGER NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
	}

	private fun createPreV8LegacyTables(db: SQLiteDatabase) {
		db.execSQL("CREATE TABLE challenge_session_data (id INTEGER NOT NULL, challenge_processed INTEGER NOT NULL, PRIMARY KEY(id))")
		db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type INTEGER NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, difficulty INTEGER NOT NULL)")
		db.execSQL("CREATE TABLE challenge_explorer (required_location_count INTEGER NOT NULL, location_count INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL)")
		db.execSQL("CREATE TABLE challenge_walk_distance (required_distance REAL NOT NULL, distance REAL NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL)")
		db.execSQL("CREATE TABLE challenge_step (requiredStepCount INTEGER NOT NULL, stepCount INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL)")
		db.execSQL("CREATE TABLE challenge_active_time (activeTimeInMinutes INTEGER NOT NULL, requiredActiveTimeInMinutes INTEGER NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entry_id INTEGER NOT NULL, completed INTEGER NOT NULL)")
	}

	private fun tableExists(table: String): Boolean =
		db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf<Any?>(table))
			.use { cursor -> cursor.moveToFirst() }

	private fun SQLiteDatabase.tableExists(table: String): Boolean =
		rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1", arrayOf(table))
			.use { cursor -> cursor.moveToFirst() }

	private fun columnNames(table: String): Set<String> =
		db.query("PRAGMA table_info(`$table`)").use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private fun indexNames(table: String): Set<String> =
		db.query("PRAGMA index_list(`$table`)").use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private fun foreignKeyTargets(table: String): Set<String> =
		db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
			}
		}

	private fun foreignKeyCheckCount(table: String): Int =
		appDatabase!!.openHelper.writableDatabase.query("PRAGMA foreign_key_check(`$table`)").use { cursor ->
			cursor.count
		}

	private fun querySingleRow(sql: String): Row =
		queryRows(sql).also { rows -> assertTrue(rows.isNotEmpty(), "Expected one row for $sql") }.first()

	private fun queryRows(sql: String): List<Row> =
		appDatabase!!.openHelper.writableDatabase.query(sql).use { cursor ->
			buildList {
				while (cursor.moveToNext()) {
					val values = (0 until cursor.columnCount).associate { index ->
						cursor.getColumnName(index) to when (cursor.getType(index)) {
							android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
							android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
							android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
							android.database.Cursor.FIELD_TYPE_NULL -> null
							else -> cursor.getBlob(index)
						}
					}
					add(Row(values))
				}
			}
		}

	private fun deleteLegacyChallengeFiles() {
		application.deleteDatabase(ChallengeDatabaseFold.DATABASE_NAME)
		listOf(
			legacyBackupFile(),
			File(application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).parentFile, "${ChallengeDatabaseFold.DATABASE_NAME}-wal"),
			File(application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).parentFile, "${ChallengeDatabaseFold.DATABASE_NAME}-shm"),
		).forEach { if (it.exists()) it.delete() }
	}

	private fun legacyBackupFile(): File = File(
		application.getDatabasePath(ChallengeDatabaseFold.DATABASE_NAME).parentFile,
		"${ChallengeDatabaseFold.DATABASE_NAME}${ChallengeDatabaseFold.BACKUP_SUFFIX}",
	)

	private class Row(private val values: Map<String, Any?>) {
		fun getLong(column: String): Long = values.getValue(column) as Long
		fun getDouble(column: String): Double = values.getValue(column) as Double
		fun getString(column: String): String = values.getValue(column) as String
	}

	private class FakeFoldMarker(
		var complete: Boolean = false,
		var unrecoverable: Boolean = false,
	) : ChallengeDatabaseFoldMarker {

		override suspend fun isComplete(): Boolean = complete

		override suspend fun markComplete() {
			complete = true
		}

		override suspend fun isLegacyDataUnrecoverable(): Boolean = unrecoverable

		override suspend fun markLegacyDataUnrecoverable() {
			unrecoverable = true
		}
	}

	companion object {
		private val V26_SCHEMA = File(
			"schemas/com.adsamcik.tracker.shared.base.database.AppDatabase/26.json"
		)
		private val LEGACY_TABLES = listOf(
			"challenge_session_data",
			"entry",
			"challenge_explorer",
			"challenge_walk_distance",
			"challenge_step",
			"challenge_active_time",
		)
	}
}
