package com.adsamcik.tracker.game.goals

import androidx.room.withTransaction
import com.adsamcik.tracker.game.progression.PlayerProgressionRepository
import com.adsamcik.tracker.game.progression.StepsGoalXpApplyResult
import com.adsamcik.tracker.points.database.PointsDatabase
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Repairs the two reversible reward projections for one exact source-qualified Steps decision.
 *
 * AppDatabase and PointsDatabase cannot share a transaction. The durable desired effect is the
 * authority: Points first accepts an idempotent monotonic receipt, then AppDatabase acknowledges
 * only if that exact effect revision is still current. A crash between those writes simply retries
 * the same receipt. XP, player profile, and its acknowledgement are atomic in AppDatabase.
 */
@Singleton
internal class StepsGoalRewardProjector @Inject constructor(
	private val database: AppDatabase,
	private val pointsDatabase: PointsDatabase,
	private val progressionRepository: PlayerProgressionRepository,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
) {
	suspend fun project(
		effectIdentity: String,
		effectRevision: Long,
	): StepsGoalRewardProjectionResult {
		require(effectIdentity.isNotBlank())
		require(effectRevision > 0L)
		val expectedGeneration = startupGate.currentGeneration
		return try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				val points = projectComponent {
					projectPoints(effectIdentity, effectRevision)
				}
				val xp = projectComponent {
					projectXp(effectIdentity, effectRevision)
				}
				StepsGoalRewardProjectionResult.Settled(points, xp)
			} ?: StepsGoalRewardProjectionResult.RetryableFailure(
				StepsGoalRewardRetryableReason.STARTUP_GENERATION_CHANGED,
			)
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			StepsGoalRewardProjectionResult.RetryableFailure(
				StepsGoalRewardRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun projectComponent(
		operation: suspend () -> StepsGoalRewardComponentResult,
	): StepsGoalRewardComponentResult = try {
		operation()
	} catch (cancellation: CancellationException) {
		throw cancellation
	} catch (_: Exception) {
		StepsGoalRewardComponentResult.RETRYABLE_FAILURE
	}

	private suspend fun projectPoints(
		effectIdentity: String,
		effectRevision: Long,
	): StepsGoalRewardComponentResult = withContext(dispatchers.io) {
		val effect = database.stepsGoalEffectDao().get(effectIdentity)
			?: return@withContext StepsGoalRewardComponentResult.SUPERSEDED
		if (effect.effectRevision != effectRevision) {
			return@withContext StepsGoalRewardComponentResult.SUPERSEDED
		}
		if (effect.pointsAppliedRevision >= effectRevision) {
			return@withContext StepsGoalRewardComponentResult.ALREADY_SETTLED
		}
		val accepted = pointsDatabase.pointsAwardedDao().applyGoalEffect(
			effectKey = effectIdentity,
			effectRevision = effectRevision,
			time = effect.firstCompletedAtMs ?: effect.updatedAtMs,
			valueMicros = effect.desiredPointsMicros,
		)
		if (!accepted) return@withContext StepsGoalRewardComponentResult.RETRYABLE_FAILURE

		val acknowledged = database.withTransaction {
			val current = database.stepsGoalEffectDao().get(effectIdentity)
			if (current?.effectRevision != effectRevision) return@withTransaction false
			database.stepsGoalEffectDao().markPointsApplied(effectIdentity, effectRevision) == 1
		}
		if (acknowledged) {
			StepsGoalRewardComponentResult.APPLIED
		} else {
			StepsGoalRewardComponentResult.SUPERSEDED
		}
	}

	private suspend fun projectXp(
		effectIdentity: String,
		effectRevision: Long,
	): StepsGoalRewardComponentResult = when (
		progressionRepository.applyStepsGoalXpInsideAcceptedGeneration(
			effectIdentity,
			effectRevision,
		)
	) {
		StepsGoalXpApplyResult.APPLIED -> StepsGoalRewardComponentResult.APPLIED
		StepsGoalXpApplyResult.SETTLED_FROM_EXISTING_RECEIPT ->
			StepsGoalRewardComponentResult.APPLIED
		StepsGoalXpApplyResult.ALREADY_SETTLED -> StepsGoalRewardComponentResult.ALREADY_SETTLED
		StepsGoalXpApplyResult.SUPERSEDED -> StepsGoalRewardComponentResult.SUPERSEDED
	}
}

internal sealed interface StepsGoalRewardProjectionResult {
	data class Settled(
		val points: StepsGoalRewardComponentResult,
		val xp: StepsGoalRewardComponentResult,
	) : StepsGoalRewardProjectionResult

	data class RetryableFailure(
		val reason: StepsGoalRewardRetryableReason,
	) : StepsGoalRewardProjectionResult
}

internal enum class StepsGoalRewardComponentResult {
	APPLIED,
	ALREADY_SETTLED,
	SUPERSEDED,
	RETRYABLE_FAILURE,
}

internal enum class StepsGoalRewardRetryableReason {
	STARTUP_GENERATION_CHANGED,
	STORAGE_UNAVAILABLE,
}
