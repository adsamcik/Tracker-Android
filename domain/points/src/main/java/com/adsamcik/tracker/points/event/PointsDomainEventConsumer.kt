package com.adsamcik.tracker.points.event

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.points.POINTS_LOG_SOURCE
import com.adsamcik.tracker.points.work.PointsWorker
import com.adsamcik.tracker.stats.api.event.DomainEvent
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes domain events to award points on session completion.
 * Replaces broadcast-based PointsSessionReceiver (deleted, was ACTION_SESSION_FINAL).
 */
@Singleton
class PointsDomainEventConsumer @Inject constructor(
	private val domainEventRepository: DomainEventRepository,
	@ApplicationContext private val context: Context,
) {
	// Single-flight guard so concurrent triggers can't double-handle the same events.
	private val processMutex = Mutex()

	/** Process any unconsumed events for the points module. */
	suspend fun processUnconsumed() = processMutex.withLock {
		while (true) {
			val batch = domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
			if (batch.isEmpty()) return@withLock

			batch.forEach { handleEvent(it.event) }
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
	 * Replaces PointsSessionReceiver.onSessionFinal().
	 * Enqueues PointsWorker with the session ID for points calculation.
	 */
	private fun onSessionEnded(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return

		val workManager = WorkManager.getInstance(context)
		val data = Data.Builder()
			.putLong(ARG_SESSION_ID, sessionId)
			.build()

		val workRequest = OneTimeWorkRequestBuilder<PointsWorker>()
			.addTag(POINTS_WORK_TAG)
			.setInputData(data)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()

		workManager.enqueueUniqueWork(
			uniqueWorkName(sessionId),
			ExistingWorkPolicy.KEEP,
			workRequest,
		)

		Logger.log(
			LogData(
				message = "SessionEnded event → scheduled points work for session $sessionId",
				source = POINTS_LOG_SOURCE,
			),
		)
	}

	companion object {
		const val CONSUMER_ID = "points-module"
		private const val ARG_SESSION_ID = "id"
		const val POINTS_WORK_TAG = "SessionPoints"

		private fun uniqueWorkName(sessionId: Long): String = "$POINTS_WORK_TAG-$sessionId"
	}
}
