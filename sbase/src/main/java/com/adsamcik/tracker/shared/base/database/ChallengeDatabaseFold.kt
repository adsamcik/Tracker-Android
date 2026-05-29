package com.adsamcik.tracker.shared.base.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.withTransaction
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

interface ChallengeDatabaseFoldMarker {
	suspend fun isComplete(): Boolean
	suspend fun markComplete()
}

enum class ChallengeDatabaseFoldResult {
	ALREADY_COMPLETE,
	NO_LEGACY_DATABASE,
	COPIED,
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
 * **Idempotency:** if a prior run copied data but crashed before writing the marker,
 * the next cold start detects existing rows in the `challenge` table and skips the copy,
 * proceeding directly to rename + mark. This prevents duplicate inserts from a partial
 * re-run.
 */
class ChallengeDatabaseFold(
	context: Context,
	private val appDatabase: AppDatabase,
	private val marker: ChallengeDatabaseFoldMarker,
) {
	private val appContext = context.applicationContext

	var lastFailure: Throwable? = null
		private set

	suspend fun foldIfNeeded(): ChallengeDatabaseFoldResult {
		if (marker.isComplete()) return ChallengeDatabaseFoldResult.ALREADY_COMPLETE

		val legacyFile = appContext.getDatabasePath(DATABASE_NAME)
		if (!legacyFile.exists()) return ChallengeDatabaseFoldResult.NO_LEGACY_DATABASE

		// Idempotency guard: if a prior run copied data but crashed before marking
		// complete, rows already exist in AppDatabase. Skip the copy and just
		// rename + mark so the retry loop terminates without duplicate inserts.
		if (appDatabase.challengeDao().countAll() > 0L) {
			Log.i(TAG, "Challenge rows already present in AppDatabase; skipping copy")
			renameToBackup(legacyFile)
			marker.markComplete()
			return ChallengeDatabaseFoldResult.COPIED
		}

		var oldDb: SQLiteDatabase? = null
		return try {
			oldDb = SQLiteDatabase.openDatabase(
				legacyFile.absolutePath,
				null,
				SQLiteDatabase.OPEN_READWRITE,
			)
			val nonEmptyLegacyTables = LEGACY_TABLES.filter { table ->
				oldDb.tableExists(table) && oldDb.countRows(table) > 0L
			}
			if (nonEmptyLegacyTables.isNotEmpty()) {
				Log.w(
					TAG,
					"Skipping challenge DB fold because legacy tables are not empty: $nonEmptyLegacyTables",
				)
				return ChallengeDatabaseFoldResult.LEGACY_TABLES_NOT_EMPTY
			}

			LEGACY_TABLES.forEach { table -> oldDb.dropTableIfExists(table) }
			copyActiveTables(oldDb)
			oldDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { /* checkpoint side effect */ }
			oldDb.close()
			oldDb = null

			renameToBackup(legacyFile)
			marker.markComplete()
			ChallengeDatabaseFoldResult.COPIED
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
			val challengeIdMap = copyChallenges(oldDb)
			val historyIdMap = copyHistory(oldDb, challengeIdMap)
			copyStreak(oldDb)
			copyXpLedger(oldDb, challengeIdMap)
			copyPlayerProfile(oldDb)
			copyPersonalRecords(oldDb, historyIdMap)
			copyMiniGameScores(oldDb)
		}
	}

	private suspend fun copyChallenges(oldDb: SQLiteDatabase): Map<Long, Long> {
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
				val newId = appDatabase.challengeDao().insert(
					ChallengeEntity(
						id = 0,
						type = ChallengeType.valueOf(cursor.getString(1)),
						startTime = cursor.getLong(2),
						endTime = cursor.getLong(3),
						difficulty = ChallengeDifficulty.valueOf(cursor.getString(4)),
						requiredValue = cursor.getDouble(5),
						currentValue = cursor.getDouble(6),
						isCompleted = cursor.getInt(7) != 0,
						extraJson = cursor.getStringOrNull(8),
					),
				)
				if (newId != -1L) idMap[oldId] = newId
			}
		}
		return idMap
	}

	private suspend fun copyHistory(
		oldDb: SQLiteDatabase,
		challengeIdMap: Map<Long, Long>,
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
				val newId = appDatabase.challengeHistoryDao().insert(
					ChallengeHistoryEntity(
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
					),
				)
				if (newId != -1L) idMap[oldId] = newId
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

	private fun Cursor.getStringOrNull(index: Int): String? =
		if (isNull(index)) null else getString(index)

	private fun Cursor.getLongOrNull(index: Int): Long? =
		if (isNull(index)) null else getLong(index)

	companion object {
		const val MARKER_KEY = "challenge_db_fold_complete_v27"
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
	}
}
