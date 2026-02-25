package com.adsamcik.tracker.game.event

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.game.CHALLENGE_LOG_SOURCE
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.challenge.worker.ChallengeWorker
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes domain events relevant to gamification (challenges, goals).
 * Replaces broadcast-based ChallengeSessionReceiver (deleted) and GoalsSessionUpdateReceiver.
 * Uses consumer-offset tracking for crash-safe, ordered delivery.
 */
@Singleton
class GameDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
) {
	/** Process any unconsumed events for the game module. */
	suspend fun processUnconsumed() {
		val events = domainEventRepository.getUnconsumed(CONSUMER_ID)
		if (events.isEmpty()) return

		var latestTimestamp = EpochMs(0L)
		for (event in events) {
			handleEvent(event)
			if (event.timestampMs.raw > latestTimestamp.raw) {
				latestTimestamp = event.timestampMs
			}
		}
		domainEventRepository.markConsumed(CONSUMER_ID, latestTimestamp)
	}

	private fun handleEvent(event: DomainEvent) {
		when (event) {
			is DomainEvent.SessionEnded -> onSessionEnded(event)
			is DomainEvent.DailySummaryUpdated -> onDailySummaryUpdated(event)
			is DomainEvent.AchievementUnlocked -> onAchievementUnlocked(event)
			is DomainEvent.AchievementProgress -> onAchievementProgress(event)
			else -> Unit
		}
	}

	/**
	 * Replaces ChallengeSessionReceiver.onReceive().
	 * Enqueues ChallengeWorker if challenges are enabled.
	 */
	private fun onSessionEnded(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return

		@Suppress("DEPRECATION")
		val challengesEnabled = Preferences.getPref(context).getBooleanRes(
			R.string.settings_game_challenge_enable_key,
			R.string.settings_game_challenge_enable_default,
		)

		if (!challengesEnabled) return

		Logger.log(
			LogData(
				message = "SessionEnded event → scheduling ChallengeWorker for session $sessionId",
				source = CHALLENGE_LOG_SOURCE,
			),
		)

		val workManager = WorkManager.getInstance(context)
		val data = Data.Builder()
			.putLong(ChallengeWorker.ARG_SESSION_ID, sessionId)
			.build()
		val workRequest = OneTimeWorkRequestBuilder<ChallengeWorker>()
			.addTag(CHALLENGE_WORK_TAG)
			.setInputData(data)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()
		workManager.enqueueUniqueWork(
			"$sessionId${ChallengeWorker.UNIQUE_WORK_NAME}",
			ExistingWorkPolicy.REPLACE,
			workRequest,
		)
	}

	/**
	 * Will replace GoalsSessionUpdateReceiver per-cycle updates in the future.
	 * Currently a no-op — GoalTracker still receives per-cycle updates via TrackerUpdateReceiver.
	 * TODO: Migrate goal infrastructure to accept event-based cumulative step counts.
	 */
	private fun onDailySummaryUpdated(@Suppress("UNUSED_PARAMETER") event: DomainEvent.DailySummaryUpdated) {
		// Goal migration deferred — requires changes to Goal/BaseGoal/StepGoal class hierarchy
		// to accept direct value-set instead of per-session delta updates.
	}

	private fun onAchievementUnlocked(event: DomainEvent.AchievementUnlocked) {
		// Achievement unlock notification — future implementation
	}

	private fun onAchievementProgress(event: DomainEvent.AchievementProgress) {
		// Achievement progress update — future implementation
	}

	companion object {
		const val CONSUMER_ID = "game-module"
		private const val CHALLENGE_WORK_TAG = "Challenge"
	}
}
