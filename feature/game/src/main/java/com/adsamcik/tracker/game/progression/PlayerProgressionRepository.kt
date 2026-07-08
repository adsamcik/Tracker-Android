package com.adsamcik.tracker.game.progression

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker
import com.adsamcik.tracker.stats.api.metric.MetricKeys
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Owns the player XP/level economy that gates mini-game unlocks.
 *
 * XP is appended to the immutable `xp_ledger` (idempotent via the unique
 * `(source, source_id)` index) and the cached `player_profile` row is then
 * recomputed from the authoritative ledger total via [XpCurve]. Both writes run
 * inside a single Room transaction so a level can never drift from its ledger.
 *
 * This restores the leveling pipeline that was removed alongside the challenge
 * system: without it nothing ever wrote `player_profile`, the level stayed at 1,
 * and every mini-game (unlock levels 3/6/9) was permanently locked.
 */
@Singleton
class PlayerProgressionRepository @Inject constructor(
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val metricDirtyTracker: MetricDirtyTracker,
) {

	/**
	 * Award passive XP for a completed tracking session. Capped per-session and
	 * by the remaining daily budget so leveling can't be farmed by spamming
	 * short sessions. Idempotent on the session id.
	 *
	 * The session's real distance/steps come from the persisted `session_segment`
	 * (via [com.adsamcik.tracker.shared.base.database.dao.TripDao.getById]) — the
	 * [DomainEvent.SessionEnded] payload itself does not carry aggregated
	 * distance/steps, mirroring how points scoring loads the session by id.
	 */
	suspend fun awardSessionXp(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return

		withContext(dispatchers.io) {
			val trip = database.tripDao().getById(sessionId) ?: return@withContext
			val amount = XpCalculator.sessionXp(
				distanceM = trip.distanceM,
				steps = trip.steps ?: 0,
				durationMs = trip.durationMs.coerceAtLeast(0L),
			)
			if (amount <= 0) return@withContext

			database.withTransaction {
				val todayStart = startOfDay(Time.nowMillis)
				val todayXp = database.xpLedgerDao().getXpSince(todayStart)
				val remaining = (XpCalculator.DAILY_CAP - todayXp).coerceAtLeast(0L)
				val capped = amount.toLong().coerceAtMost(remaining).toInt()
				if (capped <= 0) return@withTransaction

				val inserted = database.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						amount = capped,
						source = XpSource.SESSION.name,
						sourceId = sessionId,
						earnedAt = Time.nowMillis,
					),
				)
				if (inserted == -1L) return@withTransaction
				recomputePlayerProfile()
			}
		}
		metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
	}

	/**
	 * Award XP for a finished mini-game run (points credited 1:1). The run's
	 * wall-clock timestamp is the ledger source id, so each run is distinct but
	 * a retried persist of the same run is ignored.
	 */
	suspend fun awardMiniGameXp(points: Int, earnedAtMs: Long) {
		val amount = XpCalculator.miniGameXp(points)
		if (amount <= 0) return

		withContext(dispatchers.io) {
			database.withTransaction {
				val inserted = database.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						amount = amount,
						source = XpSource.MINI_GAME.name,
						sourceId = earnedAtMs,
						earnedAt = earnedAtMs,
					),
				)
				if (inserted == -1L) return@withTransaction
				recomputePlayerProfile()
			}
		}
		metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
	}

	/**
	 * Award XP for meeting a daily goal. The local epoch-day is the ledger source
	 * id, so each day's goal is credited at most once (idempotent) and the GOAL
	 * source rows double as the "daily goal met" record consumed by Perfect Week
	 * and Point Portfolio achievements.
	 */
	suspend fun awardGoalXp(earnedAtMs: Long) {
		val amount = XpCalculator.goalXp()
		if (amount <= 0) return
		val dayEpoch = Instant.ofEpochMilli(earnedAtMs)
			.atZone(ZoneId.systemDefault())
			.toLocalDate()
			.toEpochDay()

		withContext(dispatchers.io) {
			database.withTransaction {
				val inserted = database.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						amount = amount,
						source = XpSource.GOAL.name,
						sourceId = dayEpoch,
						earnedAt = earnedAtMs,
					),
				)
				if (inserted == -1L) return@withTransaction
				recomputePlayerProfile()
			}
		}
		metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
	}

	/**
	 * Recompute the cached profile from the authoritative ledger sum. Must run
	 * inside the same transaction as the ledger insert so the level reflects the
	 * just-added XP. Cheap: the ledger holds at most one row per session / run.
	 */
	private suspend fun recomputePlayerProfile() {
		val profileDao = database.playerProfileDao()
		profileDao.ensureExists()
		val current = profileDao.get() ?: PlayerProfileEntity()
		val totalXp = database.xpLedgerDao().getTotalXp()
		val snapshot = XpCurve.computeProfile(totalXp)
		profileDao.update(
			current.copy(
				totalXp = totalXp,
				level = snapshot.level,
				xpIntoCurrentLevel = snapshot.xpIntoCurrentLevel,
				xpForNextLevel = snapshot.xpForNextLevel,
			),
		)
	}

	private fun startOfDay(timeMillis: Long): Long {
		val zoneId = ZoneId.systemDefault()
		return Instant.ofEpochMilli(timeMillis)
			.atZone(zoneId)
			.toLocalDate()
			.atStartOfDay(zoneId)
			.toInstant()
			.toEpochMilli()
	}

	private companion object {
		/** Tables whose achievement rules depend on XP/level changes. */
		private val LEVELING_DIRTY_TABLES = setOf(
			MetricKeys.TABLE_XP_LEDGER,
			MetricKeys.TABLE_PLAYER_PROFILE,
		)
	}
}
