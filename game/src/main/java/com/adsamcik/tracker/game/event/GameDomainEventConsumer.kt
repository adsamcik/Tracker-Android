package com.adsamcik.tracker.game.event

import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes domain events relevant to gamification (challenges, goals).
 * Replaces broadcast-based ChallengeSessionReceiver and GoalsSessionUpdateReceiver.
 * Uses consumer-offset tracking for crash-safe, ordered delivery.
 */
@Singleton
class GameDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
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
			is DomainEvent.TripCompleted -> onTripCompleted(event)
			is DomainEvent.AchievementUnlocked -> onAchievementUnlocked(event)
			is DomainEvent.AchievementProgress -> onAchievementProgress(event)
			is DomainEvent.DailySummaryUpdated -> onDailySummaryUpdated(event)
			else -> Unit
		}
	}

	private fun onTripCompleted(event: DomainEvent.TripCompleted) {
		// Replaces ChallengeSessionReceiver.onReceive()
		// Challenge evaluation will be triggered here
	}

	private fun onAchievementUnlocked(event: DomainEvent.AchievementUnlocked) {
		// Handle achievement unlock notifications
	}

	private fun onAchievementProgress(event: DomainEvent.AchievementProgress) {
		// Handle achievement progress updates
	}

	private fun onDailySummaryUpdated(event: DomainEvent.DailySummaryUpdated) {
		// Replaces GoalsSessionUpdateReceiver
		// Goal tracking updates will be triggered here
	}

	companion object {
		const val CONSUMER_ID = "game-module"
	}
}
