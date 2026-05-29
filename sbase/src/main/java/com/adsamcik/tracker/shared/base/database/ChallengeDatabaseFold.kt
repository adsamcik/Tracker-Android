package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.data.ChallengeDifficulty
import com.adsamcik.tracker.shared.base.database.data.ChallengeEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengeHistoryEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengePersonalRecordEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengeStreakEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengeType
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChallengeDatabaseFoldMarker {
	suspend fun isComplete(): Boolean
	suspend fun markComplete()
	suspend fun isLegacyDataUnrecoverable(): Boolean
	suspend fun markLegacyDataUnrecoverable()
}

enum class ChallengeDatabaseFoldResult {
	ALREADY_COMPLETE,
	NO_LEGACY_DATABASE,
	COPIED,
	LEGACY_DATA_UNRECOVERABLE,
	LEGACY_TABLES_NOT_EMPTY,
	FAILED,
}

/**
 * One-time fold of the old standalone ChallengeDatabase into AppDatabase v27.
 *
 * **Sequencing guarantee:** data is copied inside a Room `withTransaction`, the old
 * database is closed and renamed to `.bak`, and ONLY THEN is the completion marker set.
 * If the data copy throws, the marker is never set and the next cold start retries.
 * A failed rename is non-fatal (the data is safe in AppDatabase) — a warning is logged
 * and the fold is marked complete anyway so the retry loop terminates.
 *
 * **Pre-v8 legacy data:** old `entry` + per-type tables are copied when the old unified
 * `challenge` table is absent/empty. Terminal old entries are also materialized into
 * `challenge_history`. `challenge_session_data` only tracked already-processed session ids;
 * v27 has no equivalent table, so it is intentionally retired after the challenge rows are
 * preserved.
 *
 * **Idempotency:** every copied challenge/history row is matched against existing AppDatabase
 * rows by stable contents before insert. This lets a marker-lost retry finish without
 * duplicating data, and also rescues users whose app created new v27 challenges before a
 * previous asynchronous fold had a chance to run.
 */
class ChallengeDatabaseFold(
	context: Context,
	private val appDatabase: AppDatabase,
	private val marker: ChallengeDatabaseFoldMarker,
) {
	private val appContext = context.applicationContext
	private val foldMutex = Mutex()

	@Volatile
	private var completedInProcessResult: ChallengeDatabaseFoldResult? = null

	var lastFailure: Throwable? = null
		private set

	suspend fun awaitComplete(): ChallengeDatabaseFoldResult = foldIfNeeded()

	suspend fun foldIfNeeded(): ChallengeDatabaseFoldResult {
		completedInProcessResult?.let { return it }
		if (marker.isComplete()) return remember(ChallengeDatabaseFoldResult.ALREADY_COMPLETE)
		if (marker.isLegacyDataUnrecoverable()) {
			return remember(ChallengeDatabaseFoldResult.LEGACY_DATA_UNRECOVERABLE)
		}

		return foldMutex.withLock {
			completedInProcessResult?.let { return@withLock it }
			foldIfNeededLocked().also { result ->
				if (result != ChallengeDatabaseFoldResult.FAILED &&
					result != ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE
				) {
					completedInProcessResult = result
				}
			}
		}
	}

	private fun remember(result: ChallengeDatabaseFoldResult): ChallengeDatabaseFoldResult {
		completedInProcessResult = result
		return result
	}

	private suspend fun foldIfNeededLocked(): ChallengeDatabaseFoldResult {
		lastFailure = null
		if (marker.isComplete()) return ChallengeDatabaseFoldResult.ALREADY_COMPLETE
		if (marker.isLegacyDataUnrecoverable()) return ChallengeDatabaseFoldResult.LEGACY_DATA_UNRECOVERABLE

		val legacyFile = appContext.getDatabasePath(DATABASE_NAME)
		if (!legacyFile.exists()) return ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE

		var oldDb: SQLiteDatabase? = null
		return try {
			oldDb = SQLiteDatabase.openDatabase(
				legacyFile.absolutePath,
				null,
				SQLiteDatabase.OPEN_READWRITE,
			)

			copyActiveTables(oldDb)
			LEGACY_TABLES.forEach { table -> oldDb.dropTableIfExists(table) }
			oldDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { /* checkpoint side effect */ }
			oldDb.close()
			oldDb = null

			renameToBackup(legacyFile)
			marker.markComplete()
			ChallengeDatabaseFoldResult.COPIED
		} catch (t: LegacyDatabaseUnrecoverableException) {
			lastFailure = t
			Log.e(TAG, "Challenge DB fold found unrecoverable legacy challenge data; proceeding with empty v27 challenge tables", t)
			marker.markLegacyDataUnrecoverable()
			ChallengeDatabaseFoldResult.LEGACY_DATA_UNRECOVERABLE
		} catch (t: Throwable) {
			lastFailure = t
			Log.e(TAG, "Challenge DB fold failed; legacy database left in place", t)
			ChallengeDatabaseFoldResult.FAILED
		} finally {
			oldDb?.close()
		}
	}

	private suspend fun copyActiveTables(oldDb: SQLiteDatabase) {
		appDatabase.withTransaction {
			val challengeState = ExistingRowMap(
				appDatabase.challengeDao().getAll().map { ChallengeKey.from(it) to it.id }
			)
			val historyState = ExistingRowMap(
				appDatabase.challengeHistoryDao().getAll().map { HistoryKey.from(it) to it.id }
			)

			val challengeIdMap = mutableMapOf<Long, Long>()
			challengeIdMap += copyChallenges(oldDb, challengeState)
			val legacyHistoryRows = if (oldDb.tableExists("challenge_history")) oldDb.countRows("challenge_history") else 0L
			challengeIdMap += copyPreV8LegacyChallenges(
				oldDb = oldDb,
				challengeState = challengeState,
				historyState = historyState,
				writeSyntheticHistory = legacyHistoryRows == 0L,
			)

			val historyIdMap = copyHistory(oldDb, challengeIdMap, historyState)
			copyStreak(oldDb)
			copyXpLedger(oldDb, challengeIdMap)
			copyPlayerProfile(oldDb)
			copyPersonalRecords(oldDb, historyIdMap)
			copyMiniGameScores(oldDb)
		}
	}

	private suspend fun copyChallenges(
		oldDb: SQLiteDatabase,
		challengeState: ExistingRowMap<ChallengeKey>,
	): Map<Long, Long> {
		if (!oldDb.tableExists("challenge")) return emptyMap()
		val idMap = mutableMapOf<Long, Long>()
		oldDb.rawQuery(
			"""
			SELECT id, type, start_time, end_time, difficulty, required_value,
			       current_value, is_completed, extra_json
			FROM challenge
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val oldId = cursor.getLong(0)
				val entity = ChallengeEntity(
					id = 0,
					type = ChallengeType.valueOf(cursor.getString(1)),
					startTime = cursor.getLong(2),
					endTime = cursor.getLong(3),
					difficulty = ChallengeDifficulty.valueOf(cursor.getString(4)),
					requiredValue = cursor.getDouble(5),
					currentValue = cursor.getDouble(6),
					isCompleted = cursor.getInt(7) != 0,
					extraJson = cursor.getStringOrNull(8),
				)
				idMap[oldId] = insertOrMapChallenge(entity, challengeState, rememberInserted = true)
			}
		}
		return idMap
	}

	private suspend fun copyPreV8LegacyChallenges(
		oldDb: SQLiteDatabase,
		challengeState: ExistingRowMap<ChallengeKey>,
		historyState: ExistingRowMap<HistoryKey>,
		writeSyntheticHistory: Boolean,
	): Map<Long, Long> {
		if (!oldDb.tableExists("entry")) return emptyMap()

		val typedRowCount = LEGACY_TYPED_TABLES.sumOf { spec ->
			if (oldDb.tableExists(spec.table)) oldDb.countRows(spec.table) else 0L
		}
		val entryRowCount = oldDb.countRows("entry")
		val unifiedRowCount = if (oldDb.tableExists("challenge")) oldDb.countRows("challenge") else 0L
		if (entryRowCount > 0L && typedRowCount == 0L && unifiedRowCount == 0L) {
			throw LegacyDatabaseUnrecoverableException(
				"Legacy entry table has $entryRowCount rows but no per-type challenge rows to recover required/current values",
			)
		}

		val idMap = mutableMapOf<Long, Long>()
		val now = Time.nowMillis
		LEGACY_TYPED_TABLES.forEach { spec ->
			copyPreV8TypedTable(
				oldDb = oldDb,
				spec = spec,
				now = now,
				challengeState = challengeState,
				historyState = historyState,
				writeSyntheticHistory = writeSyntheticHistory,
				idMap = idMap,
			)
		}
		if (typedRowCount > 0L && idMap.isEmpty()) {
			throw LegacyDatabaseUnrecoverableException(
				"Legacy per-type challenge rows exist but none joined to entry rows; cannot recover challenge windows",
			)
		}
		return idMap
	}

	private suspend fun copyPreV8TypedTable(
		oldDb: SQLiteDatabase,
		spec: LegacyTypedTable,
		now: Long,
		challengeState: ExistingRowMap<ChallengeKey>,
		historyState: ExistingRowMap<HistoryKey>,
		writeSyntheticHistory: Boolean,
		idMap: MutableMap<Long, Long>,
	) {
		if (!oldDb.tableExists(spec.table) || oldDb.countRows(spec.table) == 0L) return
		val requiredColumn = oldDb.requireColumn(spec.table, spec.requiredColumns)
		val currentColumn = oldDb.requireColumn(spec.table, spec.currentColumns)
		oldDb.rawQuery(
			"""
			SELECT e.id, e.start_time, e.end_time, e.difficulty,
			       c.`$requiredColumn`, c.`$currentColumn`, c.completed
			FROM `${spec.table}` c
			INNER JOIN entry e ON c.entry_id = e.id
			ORDER BY e.id, c.id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val oldEntryId = cursor.getLong(0)
				if (idMap.containsKey(oldEntryId)) continue
				val entity = ChallengeEntity(
					id = 0,
					type = spec.type,
					startTime = cursor.getLong(1),
					endTime = cursor.getLong(2),
					difficulty = parseLegacyDifficulty(cursor.getString(3)),
					requiredValue = cursor.getDouble(4),
					currentValue = cursor.getDouble(5),
					isCompleted = cursor.getInt(6) != 0,
				)
				val newId = insertOrMapChallenge(entity, challengeState, rememberInserted = false)
				idMap[oldEntryId] = newId
				if (writeSyntheticHistory && (entity.isCompleted || entity.endTime <= now)) {
					insertOrMapHistory(entity.copy(id = newId), historyState, rememberInserted = false)
				}
			}
		}
	}

	private suspend fun insertOrMapChallenge(
		entity: ChallengeEntity,
		challengeState: ExistingRowMap<ChallengeKey>,
		rememberInserted: Boolean,
	): Long {
		val key = ChallengeKey.from(entity)
		val existingId = challengeState.take(key)
		if (existingId != null) return existingId

		val newId = appDatabase.challengeDao().insert(entity)
		if (rememberInserted && newId != -1L) challengeState.add(key, newId)
		return newId
	}

	private suspend fun insertOrMapHistory(
		entity: ChallengeEntity,
		historyState: ExistingRowMap<HistoryKey>,
		rememberInserted: Boolean,
	): Long {
		val outcome = if (entity.isCompleted) "COMPLETED" else "EXPIRED"
		val historyEntry = ChallengeHistoryEntity(
			challengeType = entity.type.name,
			difficulty = entity.difficulty.name,
			startTime = entity.startTime,
			endTime = entity.endTime,
			outcome = outcome,
			completedAt = if (entity.isCompleted) entity.endTime else null,
			progressValue = entity.currentValue,
			targetValue = entity.requiredValue,
			medal = null,
			xpAwarded = 0,
			originalChallengeId = entity.id,
		)
		val key = HistoryKey.from(historyEntry)
		val existingId = historyState.take(key)
		if (existingId != null) return existingId

		val newId = appDatabase.challengeHistoryDao().insert(historyEntry)
		if (rememberInserted && newId != -1L) historyState.add(key, newId)
		return newId
	}

	private suspend fun copyHistory(
		oldDb: SQLiteDatabase,
		challengeIdMap: Map<Long, Long>,
		historyState: ExistingRowMap<HistoryKey>,
	): Map<Long, Long> {
		if (!oldDb.tableExists("challenge_history")) return emptyMap()
		val idMap = mutableMapOf<Long, Long>()
		oldDb.rawQuery(
			"""
			SELECT id, challenge_type, difficulty, start_time, end_time, outcome,
			       completed_at, progress_value, target_value, medal, xp_awarded,
			       original_challenge_id
			FROM challenge_history
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val oldId = cursor.getLong(0)
				val oldChallengeId = cursor.getLongOrNull(11)
				val historyEntry = ChallengeHistoryEntity(
					id = 0,
					challengeType = cursor.getString(1),
					difficulty = cursor.getString(2),
					startTime = cursor.getLong(3),
					endTime = cursor.getLong(4),
					outcome = cursor.getString(5),
					completedAt = cursor.getLongOrNull(6),
					progressValue = cursor.getDouble(7),
					targetValue = cursor.getDouble(8),
					medal = cursor.getStringOrNull(9),
					xpAwarded = cursor.getInt(10),
					originalChallengeId = oldChallengeId?.let { challengeIdMap[it] ?: it },
				)
				val key = HistoryKey.from(historyEntry)
				val existingId = historyState.take(key)
				if (existingId != null) {
					idMap[oldId] = existingId
				} else {
					val newId = appDatabase.challengeHistoryDao().insert(historyEntry)
					if (newId != -1L) {
						idMap[oldId] = newId
					}
				}
			}
		}
		return idMap
	}

	private suspend fun copyStreak(oldDb: SQLiteDatabase) {
		if (!oldDb.tableExists("challenge_streak")) return
		oldDb.rawQuery(
			"""
			SELECT id, current_count, best_count, last_completion_time, freeze_count
			FROM challenge_streak
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				appDatabase.challengeStreakDao().insert(
					ChallengeStreakEntity(
						id = cursor.getInt(0),
						currentCount = cursor.getInt(1),
						bestCount = cursor.getInt(2),
						lastCompletionTime = cursor.getLong(3),
						freezeCount = cursor.getInt(4),
					),
				)
			}
		}
	}

	private suspend fun copyXpLedger(
		oldDb: SQLiteDatabase,
		challengeIdMap: Map<Long, Long>,
	) {
		if (!oldDb.tableExists("xp_ledger")) return
		oldDb.rawQuery(
			"""
			SELECT id, amount, source, source_id, earned_at
			FROM xp_ledger
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val source = cursor.getString(2)
				val oldSourceId = cursor.getLongOrNull(3)
				val mappedSourceId = if (source == "CHALLENGE") {
					oldSourceId?.let { challengeIdMap[it] ?: it }
				} else {
					oldSourceId
				}
				appDatabase.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						id = 0,
						amount = cursor.getInt(1),
						source = source,
						sourceId = mappedSourceId,
						earnedAt = cursor.getLong(4),
					),
				)
			}
		}
	}

	private suspend fun copyPlayerProfile(oldDb: SQLiteDatabase) {
		if (!oldDb.tableExists("player_profile")) return
		oldDb.rawQuery(
			"""
			SELECT id, total_xp, level, xp_into_current_level, xp_for_next_level
			FROM player_profile
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				appDatabase.playerProfileDao().insert(
					PlayerProfileEntity(
						id = cursor.getInt(0),
						totalXp = cursor.getLong(1),
						level = cursor.getInt(2),
						xpIntoCurrentLevel = cursor.getLong(3),
						xpForNextLevel = cursor.getLong(4),
					),
				)
			}
		}
	}

	private suspend fun copyPersonalRecords(
		oldDb: SQLiteDatabase,
		historyIdMap: Map<Long, Long>,
	) {
		if (!oldDb.tableExists("challenge_personal_record")) return
		oldDb.rawQuery(
			"""
			SELECT id, challenge_type, metric, value, history_id, achieved_at
			FROM challenge_personal_record
			ORDER BY achieved_at DESC, id DESC
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				appDatabase.challengePersonalRecordDao().insert(
					ChallengePersonalRecordEntity(
						id = 0,
						challengeType = cursor.getString(1),
						metric = cursor.getString(2),
						value = cursor.getDouble(3),
						historyId = cursor.getLongOrNull(4)?.let { historyIdMap[it] },
						achievedAt = cursor.getLong(5),
					),
				)
			}
		}
	}

	private suspend fun copyMiniGameScores(oldDb: SQLiteDatabase) {
		if (!oldDb.tableExists("minigame_score")) return
		oldDb.rawQuery(
			"""
			SELECT id, game_id, score, xp_awarded, played_at
			FROM minigame_score
			ORDER BY id
			""".trimIndent(),
			null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				appDatabase.miniGameScoreDao().insert(
					MiniGameScoreEntity(
						id = 0,
						gameId = cursor.getString(1),
						score = cursor.getDouble(2),
						xpAwarded = cursor.getInt(3),
						playedAt = cursor.getLong(4),
					),
				)
			}
		}
	}

	private fun parseLegacyDifficulty(raw: String?): ChallengeDifficulty {
		val value = raw ?: return ChallengeDifficulty.MEDIUM
		value.toIntOrNull()?.let { ordinal ->
			return ChallengeDifficulty.entries.getOrElse(ordinal) { ChallengeDifficulty.MEDIUM }
		}
		return runCatching { ChallengeDifficulty.valueOf(value) }.getOrDefault(ChallengeDifficulty.MEDIUM)
	}

	private fun renameToBackup(legacyFile: File) {
		val backupFile = File(legacyFile.parentFile, "$DATABASE_NAME$BACKUP_SUFFIX")
		if (backupFile.exists()) {
			Log.w(TAG, "Challenge DB backup already exists at ${backupFile.absolutePath}; leaving legacy file in place")
			return
		}
		if (!legacyFile.renameTo(backupFile)) {
			Log.w(TAG, "Could not rename ${legacyFile.absolutePath} to ${backupFile.absolutePath}")
		}
	}

	private fun SQLiteDatabase.tableExists(table: String): Boolean =
		rawQuery(
			"SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
			arrayOf(table),
		).use { cursor -> cursor.moveToFirst() }

	private fun SQLiteDatabase.countRows(table: String): Long =
		rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
			if (cursor.moveToFirst()) cursor.getLong(0) else 0L
		}

	private fun SQLiteDatabase.dropTableIfExists(table: String) {
		execSQL("DROP TABLE IF EXISTS `$table`")
	}

	private fun SQLiteDatabase.columnNames(table: String): Set<String> =
		rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
			buildSet {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
			}
		}

	private fun SQLiteDatabase.requireColumn(table: String, candidates: List<String>): String {
		val columns = columnNames(table)
		return candidates.firstOrNull { it in columns }
			?: throw LegacyDatabaseUnrecoverableException(
				"Legacy table $table is missing one of expected columns $candidates; found $columns",
			)
	}

	private fun Cursor.getStringOrNull(index: Int): String? =
		if (isNull(index)) null else getString(index)

	private fun Cursor.getLongOrNull(index: Int): Long? =
		if (isNull(index)) null else getLong(index)

	private data class ChallengeKey(
		val type: ChallengeType,
		val startTime: Long,
		val endTime: Long,
		val difficulty: ChallengeDifficulty,
		val requiredValue: Double,
		val currentValue: Double,
		val isCompleted: Boolean,
		val extraJson: String?,
	) {
		companion object {
			fun from(entity: ChallengeEntity): ChallengeKey = ChallengeKey(
				type = entity.type,
				startTime = entity.startTime,
				endTime = entity.endTime,
				difficulty = entity.difficulty,
				requiredValue = entity.requiredValue,
				currentValue = entity.currentValue,
				isCompleted = entity.isCompleted,
				extraJson = entity.extraJson,
			)
		}
	}

	private data class HistoryKey(
		val challengeType: String,
		val difficulty: String,
		val startTime: Long,
		val endTime: Long,
		val outcome: String,
		val completedAt: Long?,
		val progressValue: Double,
		val targetValue: Double,
		val medal: String?,
		val xpAwarded: Int,
		val originalChallengeId: Long?,
	) {
		companion object {
			fun from(entity: ChallengeHistoryEntity): HistoryKey = HistoryKey(
				challengeType = entity.challengeType,
				difficulty = entity.difficulty,
				startTime = entity.startTime,
				endTime = entity.endTime,
				outcome = entity.outcome,
				completedAt = entity.completedAt,
				progressValue = entity.progressValue,
				targetValue = entity.targetValue,
				medal = entity.medal,
				xpAwarded = entity.xpAwarded,
				originalChallengeId = entity.originalChallengeId,
			)
		}
	}

	private class ExistingRowMap<K>(rows: List<Pair<K, Long>>) {
		private val idsByKey: MutableMap<K, MutableList<Long>> = mutableMapOf()

		init {
			rows.forEach { (key, id) -> add(key, id) }
		}

		fun take(key: K): Long? {
			val ids = idsByKey[key] ?: return null
			return if (ids.isEmpty()) null else ids.removeAt(0)
		}

		fun add(key: K, id: Long) {
			idsByKey.getOrPut(key) { mutableListOf() }.add(id)
		}
	}

	private data class LegacyTypedTable(
		val table: String,
		val type: ChallengeType,
		val requiredColumns: List<String>,
		val currentColumns: List<String>,
	)

	private class LegacyDatabaseUnrecoverableException(message: String) : SQLiteException(message)

	companion object {
		const val MARKER_KEY = "challenge_db_fold_complete_v27"
		const val UNRECOVERABLE_MARKER_KEY = "legacy_data_unrecoverable_v27"
		const val DATABASE_NAME = "challenge_database"
		const val BACKUP_SUFFIX = ".bak"
		private const val TAG = "ChallengeDbFold"
		private val LEGACY_TABLES = listOf(
			"challenge_session_data",
			"entry",
			"challenge_explorer",
			"challenge_walk_distance",
			"challenge_step",
			"challenge_active_time",
		)
		private val LEGACY_TYPED_TABLES = listOf(
			LegacyTypedTable(
				table = "challenge_explorer",
				type = ChallengeType.Explorer,
				requiredColumns = listOf("required_location_count", "requiredLocationCount"),
				currentColumns = listOf("location_count", "locationCount"),
			),
			LegacyTypedTable(
				table = "challenge_walk_distance",
				type = ChallengeType.WalkDistance,
				requiredColumns = listOf("required_distance", "requiredDistanceInM"),
				currentColumns = listOf("distance", "distanceInM"),
			),
			LegacyTypedTable(
				table = "challenge_step",
				type = ChallengeType.Step,
				requiredColumns = listOf("requiredStepCount"),
				currentColumns = listOf("stepCount"),
			),
			LegacyTypedTable(
				table = "challenge_active_time",
				type = ChallengeType.ActiveTime,
				requiredColumns = listOf("requiredActiveTimeInMinutes"),
				currentColumns = listOf("activeTimeInMinutes"),
			),
		)
	}
}
