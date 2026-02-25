package com.adsamcik.tracker.activity.event

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes domain events to trigger activity recognition on session completion.
 * Replaces broadcast-based ActivitySessionReceiver (ACTION_SESSION_ENDED).
 */
@Singleton
class ActivityDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
) {
	/** Process any unconsumed events for the activity module. */
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
			else -> Unit
		}
	}

	/**
	 * Replaces ActivitySessionReceiver.onSessionEnded().
	 * Enqueues ActivityRecognitionWorker with the session ID.
	 */
	private fun onSessionEnded(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return

		val data = Data.Builder()
			.putLong(ActivityRecognitionWorker.ARG_SESSION_ID, sessionId)
			.build()
		val workRequest = OneTimeWorkRequestBuilder<ActivityRecognitionWorker>()
			.addTag(ActivityRecognitionWorker.WORK_TAG)
			.setInputData(data)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()

		WorkManager.getInstance(context).enqueue(workRequest)
	}

	companion object {
		const val CONSUMER_ID = "activity-module"
	}
}
