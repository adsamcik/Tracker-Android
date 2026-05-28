package com.adsamcik.tracker.activity.event

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
	// Single-flight guard so concurrent triggers (worker catch-up + session-end emission)
	// can't double-handle the same events.
	private val processMutex = Mutex()

	/** Process any unconsumed events for the activity module. */
	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val batch = domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
			if (batch.isEmpty()) return@withLock

			batch.forEach { handleEvent(it.event) }
			// Ack the LAST event by composite (timestamp, id) so same-millisecond rows
			// outside the bounded batch are NOT skipped on the next fetch.
			val last = batch.last()
			domainEventRepository.markBatchConsumed(
				consumerId = CONSUMER_ID,
				upToTimestamp = last.event.timestampMs,
				upToEventId = last.persistedId,
			)
		}
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

		WorkManager.getInstance(context).enqueueUniqueWork(
			uniqueWorkName(sessionId),
			ExistingWorkPolicy.KEEP,
			workRequest,
		)
	}

	companion object {
		const val CONSUMER_ID = "activity-module"
		private const val UNIQUE_WORK_PREFIX = "ActivityRecognition-session"

		fun uniqueWorkName(sessionId: Long): String = "$UNIQUE_WORK_PREFIX-$sessionId"
	}
}
