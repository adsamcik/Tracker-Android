package com.adsamcik.tracker.game.progression

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.data.XpLedgerEntity
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
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
	private val trackingStartupGate: TrackingStartupGate,
) {

	/**
	 * Award passive XP for a completed tracking session. Capped per-session and
	 * by the remaining daily budget so leveling can't be farmed by spamming
	 * short sessions. Idempotent on the session id.
	 *
	 * The session's distance and duration come from the persisted `session_segment` — the
	 * [DomainEvent.SessionEnded] payload itself does not carry the authoritative aggregates,
	 * mirroring how points scoring loads the session by id. The lookup and award share one Room
	 * transaction inside the deletion generation fence. The legacy segment Steps value is
	 * intentionally excluded until a source-qualified award decision exists.
	 */
	suspend fun awardSessionXp(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return
		val expectedGeneration = trackingStartupGate.currentGeneration

		val inserted = runAcceptedXpMutation(expectedGeneration) {
			val segment = database.sessionSegmentDao().getById(sessionId)
				?: return@runAcceptedXpMutation false
			val amount = XpCalculator.sessionXp(
				distanceM = segment.distanceM,
				durationMs = (segment.endTimeMs - segment.startTimeMs).coerceAtLeast(0L),
			)
			if (amount <= 0) return@runAcceptedXpMutation false

			val todayStart = startOfDay(Time.nowMillis)
			val todayXp = database.xpLedgerDao().getXpSince(todayStart)
			val remaining = (XpCalculator.DAILY_CAP - todayXp).coerceAtLeast(0L)
			val capped = amount.toLong().coerceAtMost(remaining).toInt()
			if (capped <= 0) return@runAcceptedXpMutation false

			val ledgerId = database.xpLedgerDao().insertOrIgnore(
				XpLedgerEntity(
					amount = capped,
					source = XpSource.SESSION.name,
					sourceId = sessionId,
					earnedAt = Time.nowMillis,
				),
			)
			if (ledgerId == -1L) return@runAcceptedXpMutation false
			recomputePlayerProfile()
			true
		}
		if (inserted == true) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
	}

	/**
	 * Repairs the XP half of one mini-game reward while the caller holds the accepted startup-gate
	 * operation. The caller owns deletion serialization so this method must not acquire a nested
	 * [TrackingStartupGate.withReadyGenerationOperation] lease.
	 *
	 * @return `true` only when this call inserted a new XP ledger row.
	 */
	internal suspend fun awardMiniGameXpInsideAcceptedGeneration(
		points: Int,
		earnedAtMs: Long,
	): Boolean {
		val amount = XpCalculator.miniGameXp(points)
		if (amount <= 0) return false

		val inserted = withContext(dispatchers.io) {
			database.withTransaction {
				val ledgerId = database.xpLedgerDao().insertOrIgnore(
					XpLedgerEntity(
						amount = amount,
						source = XpSource.MINI_GAME.name,
						sourceId = earnedAtMs,
						earnedAt = earnedAtMs,
					),
				)
				if (ledgerId == -1L) return@withTransaction false
				recomputePlayerProfile()
				true
			}
		}
		if (inserted) metricDirtyTracker.markDirty(LEVELING_DIRTY_TABLES)
		return inserted
	}

	/**
	 * Captures the deletion generation before the first suspension and keeps the complete Room
	 * mutation inside the gate's serialized operation. A deletion that closes or replaces that
	 * generation therefore either waits for this transaction or rejects it before any row is read.
	 */
	private suspend fun runAcceptedXpMutation(
		expectedGeneration: Long,
		mutation: suspend () -> Boolean,
	): Boolean? {
		if (trackingStartupGate.reconcile() !is TrackingStartupResult.Ready) return null
		return trackingStartupGate.withReadyGenerationOperation(expectedGeneration) {
			withContext(dispatchers.io) {
				database.withTransaction { mutation() }
			}
		}
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
