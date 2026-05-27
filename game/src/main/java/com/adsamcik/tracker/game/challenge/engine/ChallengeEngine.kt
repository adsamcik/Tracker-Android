package com.adsamcik.tracker.game.challenge.engine

import androidx.room.withTransaction
import com.adsamcik.tracker.game.challenge.catalog.ChallengeCatalog
import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.progression.ProgressionRepository
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.stats.api.metric.TimeWindow
import com.adsamcik.tracker.stats.api.repository.WindowedMetricsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Declarative challenge evaluator.
 *
 * Replaces the per-type
 * [com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor]
 * imperative loop with: lookup
 * [com.adsamcik.tracker.game.challenge.catalog.ChallengeDefinition] -> call
 * [WindowedMetricsProvider] -> compare to target -> emit updated entity. All write
 * side-effects (entity updates + progression history / xp / streak / personal records via
 * [ProgressionRepository]) commit inside ONE Room transaction so a mid-flight crash
 * leaves the database fully consistent.
 */
@Singleton
class ChallengeEngine @Inject constructor(
	private val challengeDatabase: ChallengeDatabase,
	private val metrics: WindowedMetricsProvider,
	private val progression: ProgressionRepository,
) {

	/**
	 * Apply a finished [session] to all currently active, not-yet-completed challenges.
	 *
	 * Returns the set of challenges that transitioned to `isCompleted = true` during this
	 * call (each only once), so the caller can fire notifications. The transition itself,
	 * along with all derived progression writes, is already committed by the time this
	 * method returns.
	 */
	suspend fun applySession(
		session: TrackerSession,
	): EngineResult {
		val now = Time.nowMillis
		val challengeDao = challengeDatabase.challengeDao()
		val active: List<ChallengeEntity> = challengeDao.getActive(now)

		if (active.isEmpty()) {
			// Still award passive session XP — gated internally by the daily cap.
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		// Precompute deltas OUTSIDE the write transaction so metric reads don't hold a
		// transaction open longer than needed. The metric provider only reads pre-aggregated
		// tables that won't change during the brief write window.
		val proposedUpdates: List<ProposedUpdate> = active.mapNotNull { entity ->
			evaluateOne(entity, now)
		}

		if (proposedUpdates.isEmpty()) {
			progression.onTrackingSession(session)
			return EngineResult(updates = emptyList(), newlyCompleted = emptyList())
		}

		val completedEntities = mutableListOf<ChallengeEntity>()

		challengeDatabase.withTransaction {
			for (update in proposedUpdates) {
				challengeDao.update(update.updated)
				if (update.justCompleted) {
					completedEntities += update.updated
				}
			}

			// Progression for each completion (history, xp, streak, PR) — its inner
			// withTransaction blocks join THIS outer transaction (Room is reentrant).
			for (entity in completedEntities) {
				val def = ChallengeCatalog.byType(entity.type)
				val instance = ChallengeInstanceNew.fromDefinition(entity, def)
				progression.onChallengeCompleted(instance)
			}
		}

		// Award passive session XP AFTER the completion transaction so the daily cap
		// interacts predictably with completion XP.
		progression.onTrackingSession(session)

		return EngineResult(
			updates = proposedUpdates.map { it.updated },
			newlyCompleted = completedEntities,
		)
	}

	private suspend fun evaluateOne(
		entity: ChallengeEntity,
		now: Long,
	): ProposedUpdate? {
		val def = ChallengeCatalog.byType(entity.type)

		val windowEnd = minOf(now, entity.endTime)
		if (windowEnd <= entity.startTime) return null

		val window = TimeWindow.Interval(entity.startTime, windowEnd)
		val collected = metrics.collect(def.metric, window).toDouble()

		// The catalog-driven metric query returns the ABSOLUTE value over the challenge
		// window, so progress == collected (no per-session deltas to accumulate). This is
		// a clean improvement over the legacy processor model where each extractProgress
		// returned a delta and required correct accumulation.
		val newCurrent = collected
		val newCompleted = newCurrent >= entity.requiredValue
		if (newCurrent == entity.currentValue && newCompleted == entity.isCompleted) return null

		val updated = entity.copy(
			currentValue = newCurrent,
			isCompleted = newCompleted,
		)
		val justCompleted = !entity.isCompleted && newCompleted
		return ProposedUpdate(updated, justCompleted)
	}

	private data class ProposedUpdate(val updated: ChallengeEntity, val justCompleted: Boolean)

	/**
	 * Outcome of a single [applySession] call.
	 *
	 * @property updates Every entity whose row changed (progress and/or completion flag).
	 * @property newlyCompleted Subset of [updates] that transitioned from incomplete to
	 *   completed during this call. Callers fire completion notifications for these.
	 */
	data class EngineResult(
		val updates: List<ChallengeEntity>,
		val newlyCompleted: List<ChallengeEntity>,
	)
}
