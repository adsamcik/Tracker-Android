package com.adsamcik.tracker.game.challenge.progression

import androidx.room.withTransaction
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.data.ChallengeOutcome
import com.adsamcik.tracker.game.challenge.data.Medal
import com.adsamcik.tracker.game.challenge.data.XpSource
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ChallengeHistoryEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengePersonalRecordEntity
import com.adsamcik.tracker.shared.base.database.data.ChallengeStreakEntity
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central progression coordinator.
 * Wires challenge lifecycle events to medals, XP, streaks, history, and records.
 */
@Singleton
class ProgressionRepository @Inject constructor(
	private val xpCalculator: XpCalculator,
	private val streakManager: StreakManager,
	private val challengeDatabase: AppDatabase,
) {

	/**
	 * Called when a challenge is completed.
	 * Assigns medal, records history, awards XP, updates streak, checks records.
	 *
	 * All side effects (streak / XP ledger / profile / history / personal records) are
	 * committed inside a single Room transaction so a mid-flight crash leaves the database
	 * either fully updated or unchanged. The inner [streakManager] / [awardXpAndUpdateProfile]
	 * / [checkPersonalRecords] each call `withTransaction` again; Room's reentrant transaction
	 * support means they observe and join the outer transaction rather than opening their own.
	 *
	 * @return Summary of progression events
	 */
	suspend fun onChallengeCompleted(
		instance: ChallengeInstanceNew,
	): CompletionResult {
		val database = challengeDatabase
		val now = Time.nowMillis
		val entity = instance.entity

		var resultHolder: CompletionResult? = null
		database.withTransaction {
			// 1. Assign medal based on completion timing (pure)
			val medal = Medal.fromCompletion(
				completedAt = now,
				startTime = entity.startTime,
				endTime = entity.endTime,
			)

			// 2. Update streak (joins this transaction)
			val streak = streakManager.onChallengeCompleted(database, now)

			// 3. Calculate and award XP (joins this transaction)
			val xpAward = xpCalculator.calculateChallengeXp(
				difficulty = entity.difficulty,
				medal = medal,
				streakCount = streak.currentCount,
			)
			val xpEntry = XpLedgerEntity(
				amount = xpAward.amount,
				source = XpSource.CHALLENGE.name,
				sourceId = entity.id,
				earnedAt = now,
			)
			val awardResult = awardXpAndUpdateProfile(database, xpEntry)
			val awardedXp = awardResult?.awardedXp ?: 0

			// 4. Record in history
			val historyEntry = ChallengeHistoryEntity(
				challengeType = entity.type.name,
				difficulty = entity.difficulty.name,
				startTime = entity.startTime,
				endTime = entity.endTime,
				outcome = ChallengeOutcome.COMPLETED.name,
				completedAt = now,
				progressValue = entity.currentValue,
				targetValue = entity.requiredValue,
				medal = medal.name,
				xpAwarded = awardedXp,
				originalChallengeId = entity.id,
			)
			val historyId = database.challengeHistoryDao().insert(historyEntry)

			// 5. Check personal records (joins this transaction)
			val newRecords = checkPersonalRecords(database, entity.type.name, entity, historyId, now)

			// 6. Resolve profile snapshot
			val profileSnapshot = awardResult ?: run {
				val current = database.playerProfileDao().get() ?: PlayerProfileEntity()
				AwardResult(0, current, false)
			}

			resultHolder = CompletionResult(
				medal = medal,
				xpAwarded = awardedXp,
				streakCount = streak.currentCount,
				newRecords = newRecords,
				leveledUp = profileSnapshot.leveledUp,
				newLevel = profileSnapshot.profile.level,
			)
		}
		return resultHolder!!
	}

	/**
	 * Called when challenges expire (batch).
	 * Records history for each, updates streak (freeze or break).
	 *
	 * Wrapped in a single Room transaction so the history inserts and the streak read-
	 * modify-write commit atomically. Without this, a concurrent `onChallengeCompleted`
	 * could interleave between the streak read and write and silently lose its increment.
	 */
	suspend fun onChallengesExpired(
		expired: List<ChallengeInstanceNew>,
	): ExpiryResult {
		val database = challengeDatabase

		var streakResult: Pair<ChallengeStreakEntity, Boolean> =
			ChallengeStreakEntity() to false
		database.withTransaction {
			// Record each expired challenge in history
			expired.forEach { instance ->
				val entity = instance.entity
				val historyEntry = ChallengeHistoryEntity(
					challengeType = entity.type.name,
					difficulty = entity.difficulty.name,
					startTime = entity.startTime,
					endTime = entity.endTime,
					outcome = ChallengeOutcome.EXPIRED.name,
					completedAt = null,
					progressValue = entity.currentValue,
					targetValue = entity.requiredValue,
					medal = null,
					xpAwarded = 0,
					originalChallengeId = entity.id,
				)
				database.challengeHistoryDao().insert(historyEntry)
			}

			// Update streak inside the same transaction so a racing onChallengeCompleted
			// can't slip an increment between this read-modify-write.
			streakResult = streakManager.onChallengesExpired(database)
		}

		val (streak, froze) = streakResult
		return ExpiryResult(
			expiredCount = expired.size,
			streakBroken = !froze && expired.isNotEmpty(),
			freezeUsed = froze,
			currentStreak = streak.currentCount,
		)
	}

	/**
	 * Called after a tracking session to award passive XP.
	 */
	suspend fun onTrackingSession(
		session: TrackerSession,
		isVehicleOrStill: Boolean = false,
	) {
		val database = challengeDatabase
		val xpAward = xpCalculator.calculateSessionXp(session, isVehicleOrStill)
		if (xpAward.amount <= 0) return

		database.withTransaction {
			val now = Time.nowMillis
			val todayStart = startOfDay(now)
			val todayXp = database.xpLedgerDao().getXpSince(todayStart)
			val cappedAmount = (xpAward.amount).coerceAtMost(
				(XpCalculator.DAILY_CAP - todayXp.toInt()).coerceAtLeast(0),
			)
			if (cappedAmount <= 0) return@withTransaction

			val xpEntry = XpLedgerEntity(
				amount = cappedAmount,
				source = XpSource.SESSION.name,
				sourceId = session.id,
				earnedAt = now,
			)
			val insertId = database.xpLedgerDao().insertOrIgnore(xpEntry)
			if (insertId == -1L) return@withTransaction
			updatePlayerProfile(database, addedXp = cappedAmount.toLong())
		}
	}

	/**
	 * Atomically awards XP and updates the player profile in a single transaction.
	 * Uses insertOrIgnore for idempotency — duplicate awards (same source+source_id)
	 * are silently skipped and return null.
	 */
	private suspend fun awardXpAndUpdateProfile(
		database: AppDatabase,
		xpEntry: XpLedgerEntity,
	): AwardResult? {
		var result: AwardResult? = null
		database.withTransaction {
			val insertId = database.xpLedgerDao().insertOrIgnore(xpEntry)
			if (insertId == -1L) return@withTransaction
			val (profile, leveledUp) = updatePlayerProfile(database, addedXp = xpEntry.amount.toLong())
			result = AwardResult(xpEntry.amount, profile, leveledUp)
		}
		return result
	}

	private suspend fun checkPersonalRecords(
		database: AppDatabase,
		challengeType: String,
		entity: com.adsamcik.tracker.shared.base.database.data.ChallengeEntity,
		historyId: Long,
		now: Long,
	): List<String> {
		var records: List<String>? = null
		database.withTransaction {
			val recordDao = database.challengePersonalRecordDao()
			val newRecords = mutableListOf<String>()

			// Check highest value record
			val highestValue = recordDao.get(challengeType, "HIGHEST_VALUE")
			if (highestValue == null || entity.currentValue > highestValue.value) {
				val record = ChallengePersonalRecordEntity(
					id = highestValue?.id ?: 0,
					challengeType = challengeType,
					metric = "HIGHEST_VALUE",
					value = entity.currentValue,
					historyId = historyId,
					achievedAt = now,
				)
				if (highestValue == null) recordDao.insert(record) else recordDao.update(record)
				newRecords.add("HIGHEST_VALUE")
			}

			// Check fastest completion (time used as fraction of allowed time)
			val duration = entity.endTime - entity.startTime
			if (duration > 0) {
				val timeUsed = now - entity.startTime
				val speedRatio = timeUsed.toDouble() / duration
				val fastestRecord = recordDao.get(challengeType, "FASTEST_COMPLETION")
				if (fastestRecord == null || speedRatio < fastestRecord.value) {
					val record = ChallengePersonalRecordEntity(
						id = fastestRecord?.id ?: 0,
						challengeType = challengeType,
						metric = "FASTEST_COMPLETION",
						value = speedRatio,
						historyId = historyId,
						achievedAt = now,
					)
					if (fastestRecord == null) recordDao.insert(record) else recordDao.update(record)
					newRecords.add("FASTEST_COMPLETION")
				}
			}
			records = newRecords
		}
		return records!!
	}

	/**
	 * Updates player profile incrementally without scanning the XP ledger.
	 *
	 * [addedXp] is the amount of XP just credited (in the same transaction); the new total
	 * is `current.totalXp + addedXp`. This eliminates the O(N) `SUM(amount) FROM xp_ledger`
	 * scan that previously ran on every grant and made completion latency grow with history.
	 *
	 * Self-heals on first call if [PlayerProfileEntity.totalXp] is 0 but the ledger has
	 * existing rows (legacy upgrade path): we reconcile via SUM once, then stay incremental.
	 *
	 * @return Pair of (updated profile, didLevelUp)
	 */
	private suspend fun updatePlayerProfile(
		database: AppDatabase,
		addedXp: Long,
	): Pair<PlayerProfileEntity, Boolean> {
		val profileDao = database.playerProfileDao()
		profileDao.ensureExists()
		val current = profileDao.get() ?: PlayerProfileEntity()

		val baseXp = if (current.totalXp == 0L) {
			// First call (or post-upgrade) — reconcile against ledger once.
			database.xpLedgerDao().getTotalXp()
		} else {
			current.totalXp
		}
		val totalXp = baseXp + addedXp
		val snapshot = XpCurve.computeProfile(totalXp)
		val didLevelUp = snapshot.level > current.level

		val updated = current.copy(
			totalXp = totalXp,
			level = snapshot.level,
			xpIntoCurrentLevel = snapshot.xpIntoCurrentLevel,
			xpForNextLevel = snapshot.xpForNextLevel,
		)
		profileDao.update(updated)
		return updated to didLevelUp
	}

	private data class AwardResult(
		val awardedXp: Int,
		val profile: PlayerProfileEntity,
		val leveledUp: Boolean,
	)

	private fun startOfDay(timeMillis: Long): Long {
		val zoneId = ZoneId.systemDefault()
		return Instant.ofEpochMilli(timeMillis)
			.atZone(zoneId)
			.toLocalDate()
			.atStartOfDay(zoneId)
			.toInstant()
			.toEpochMilli()
	}
}

/**
 * Result of challenge completion progression.
 */
data class CompletionResult(
	val medal: Medal,
	val xpAwarded: Int,
	val streakCount: Int,
	val newRecords: List<String>,
	val leveledUp: Boolean,
	val newLevel: Int,
)

/**
 * Result of challenge expiry processing.
 */
data class ExpiryResult(
	val expiredCount: Int,
	val streakBroken: Boolean,
	val freezeUsed: Boolean,
	val currentStreak: Int,
)
