package com.adsamcik.tracker.app.event

import android.content.Context
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import com.adsamcik.tracker.shared.preferences.R as PrefR

/**
 * Consumes [DomainEvent.SessionEnded] to trigger contextual precision upgrade prompts.
 * Replaces the broadcast-based [com.adsamcik.tracker.app.tracker.receiver.PrecisionUpgradeReceiver].
 *
 * Flow:
 * 1. User completes 2+ sessions with APPROXIMATE location mode
 * 2. After threshold, sets flag for UI to show upgrade prompt
 * 3. User can upgrade or dismiss (flag prevents re-prompts)
 */
@Singleton
class PrecisionUpgradeDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
	private val preferences: Preferences,
) {
	// Single-flight guard so concurrent triggers can't double-handle the same events.
	private val processMutex = Mutex()

	/** Process any unconsumed events for the precision-upgrade module. */
	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val batch = domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
			if (batch.isEmpty()) return@withLock

			batch.forEach { unconsumed ->
				handleEvent(unconsumed.event)
				// The preference store and cursor are non-transactional, so a crash between
				// them can still redeliver this event once; per-event ack narrows that window.
				domainEventRepository.markBatchConsumed(
					consumerId = CONSUMER_ID,
					upToTimestamp = unconsumed.event.timestampMs,
					upToEventId = unconsumed.persistedId,
				)
			}
		}
	}

	private suspend fun handleEvent(event: DomainEvent) {
		when (event) {
			is DomainEvent.SessionEnded -> onSessionEnded()
			else -> Unit
		}
	}

	private suspend fun onSessionEnded() {
		Logger.log(
			LogData(
				message = "Session finalized, checking precision upgrade eligibility",
				source = LOG_SOURCE,
			),
		)

		val prefs = preferences

		// Check if user already dismissed the prompt
		val dismissedKey = context.getString(PrefR.string.settings_precision_upgrade_dismissed_key)
		val wasDismissed = prefs.fetchBoolean(dismissedKey, false)
		if (wasDismissed) {
			Logger.log(
				LogData(
					message = "Precision upgrade prompt previously dismissed",
					source = LOG_SOURCE,
				),
			)
			return
		}

		// Check current location precision mode
		val precisionMode = prefs.fetchStringRes(PrefR.string.settings_location_precision_key)
			?: context.getString(PrefR.string.settings_location_precision_default)

		if (precisionMode != "APPROXIMATE") {
			Logger.log(
				LogData(
					message = "User already in PRECISE mode, skipping upgrade prompt",
					source = LOG_SOURCE,
				),
			)
			return
		}

		// Increment session counter for approximate mode
		val key = context.getString(PrefR.string.settings_approximate_session_count_key)
		val currentCount = prefs.fetchInt(key, 0)
		val newCount = currentCount + 1

		prefs.edit {
			setInt(PrefR.string.settings_approximate_session_count_key, newCount)
		}

		Logger.log(
			LogData(
				message = "Incremented approximate session count to $newCount",
				source = LOG_SOURCE,
			),
		)

		if (newCount >= UPGRADE_PROMPT_THRESHOLD) {
			Logger.log(
				LogData(
					message = "Reached threshold ($UPGRADE_PROMPT_THRESHOLD sessions), setting upgrade prompt flag",
					source = LOG_SOURCE,
				),
			)
			prefs.edit {
				setBoolean(PrefR.string.settings_should_show_precision_upgrade_key, true)
			}
		}
	}

	companion object {
		const val CONSUMER_ID = "precision-upgrade"
		private const val LOG_SOURCE = "PrecisionUpgrade"
		private const val UPGRADE_PROMPT_THRESHOLD = 2
	}
}
