package com.adsamcik.tracker.game.goals

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.game.goals.data.abstraction.BaseGoal
import com.adsamcik.tracker.game.goals.data.abstraction.buildGoalReachedNotification
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepsGoalEffectEntity
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.shared.base.notification.Notifications
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Claims at most one notification for a source-qualified Steps goal period. */
@Singleton
internal class StepsGoalNotificationDispatcher @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: AppDatabase,
	private val dispatchers: DispatchersProvider,
	private val startupGate: TrackingStartupGate,
) {
	suspend fun dispatch(
		effectIdentity: String,
		effectRevision: Long,
		notificationsEnabled: Boolean,
		claimedAtMs: Long,
	): StepsGoalNotificationDispatchResult {
		require(effectIdentity.isNotBlank())
		require(effectRevision > 0L)
		require(claimedAtMs >= 0L)
		val expectedGeneration = startupGate.currentGeneration
		return try {
			startupGate.withReadyGenerationOperation(expectedGeneration) {
				val claim = claim(effectIdentity, effectRevision, claimedAtMs)
				if (claim !is NotificationClaim.Claimed) return@withReadyGenerationOperation claim.result
				if (!notificationsEnabled) {
					return@withReadyGenerationOperation
						StepsGoalNotificationDispatchResult.CLAIMED_SUPPRESSED_DISABLED
				}
				try {
					context.notificationManager.notify(
						Notifications.uniqueNotificationId(),
						buildGoalReachedNotification(context, claim.period),
					)
					StepsGoalNotificationDispatchResult.CLAIMED_DELIVERY_ATTEMPTED
				} catch (_: RuntimeException) {
					// The claim is intentionally not reopened: at-most-once delivery is safer than a
					// duplicate notification after an ambiguous platform handoff.
					StepsGoalNotificationDispatchResult.CLAIMED_DELIVERY_FAILED
				}
			} ?: StepsGoalNotificationDispatchResult.RETRYABLE_GENERATION_CHANGED
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: Exception) {
			StepsGoalNotificationDispatchResult.RETRYABLE_STORAGE_UNAVAILABLE
		}
	}

	private suspend fun claim(
		effectIdentity: String,
		effectRevision: Long,
		claimedAtMs: Long,
	): NotificationClaim = withContext(dispatchers.io) {
		database.withTransaction {
			val effect = database.stepsGoalEffectDao().get(effectIdentity)
				?: return@withTransaction NotificationClaim.Superseded
			if (effect.effectRevision != effectRevision) {
				return@withTransaction NotificationClaim.Superseded
			}
			if (effect.notificationClaimedRevision != null) {
				return@withTransaction NotificationClaim.AlreadyClaimed
			}
			if (effect.decisionState != StepsGoalEffectEntity.STATE_READY_COMPLETE) {
				return@withTransaction NotificationClaim.NotEligible
			}
			check(
				database.stepsGoalEffectDao().claimNotification(
					effectIdentity,
					effectRevision,
					claimedAtMs,
				) == 1,
			) { "Current Steps goal notification could not be claimed" }
			NotificationClaim.Claimed(
				when (effect.periodKind) {
					StepsGoalEffectEntity.PERIOD_DAY -> BaseGoal.GoalPeriod.Day
					StepsGoalEffectEntity.PERIOD_WEEK -> BaseGoal.GoalPeriod.Week
					else -> error("Unsupported Steps goal period ${effect.periodKind}")
				},
			)
		}
	}

	private sealed interface NotificationClaim {
		val result: StepsGoalNotificationDispatchResult

		data class Claimed(val period: BaseGoal.GoalPeriod) : NotificationClaim {
			override val result = StepsGoalNotificationDispatchResult.CLAIMED_DELIVERY_ATTEMPTED
		}

		data object AlreadyClaimed : NotificationClaim {
			override val result = StepsGoalNotificationDispatchResult.ALREADY_CLAIMED
		}

		data object NotEligible : NotificationClaim {
			override val result = StepsGoalNotificationDispatchResult.NOT_ELIGIBLE
		}

		data object Superseded : NotificationClaim {
			override val result = StepsGoalNotificationDispatchResult.SUPERSEDED
		}
	}
}

internal enum class StepsGoalNotificationDispatchResult {
	CLAIMED_DELIVERY_ATTEMPTED,
	CLAIMED_DELIVERY_FAILED,
	CLAIMED_SUPPRESSED_DISABLED,
	ALREADY_CLAIMED,
	NOT_ELIGIBLE,
	SUPERSEDED,
	RETRYABLE_GENERATION_CHANGED,
	RETRYABLE_STORAGE_UNAVAILABLE,
}
