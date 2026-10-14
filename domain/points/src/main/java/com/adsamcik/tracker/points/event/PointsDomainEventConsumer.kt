package com.adsamcik.tracker.points.event

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
	private val appDatabaseProvider: javax.inject.Provider<com.adsamcik.tracker.shared.base.database.AppDatabase>,
	private val pointsDatabaseProvider: javax.inject.Provider<com.adsamcik.tracker.points.database.PointsDatabase>,
	private val trackingStartupGate: com.adsamcik.tracker.shared.base.startup.TrackingStartupGate,
) {
	// Single-flight guard so concurrent triggers can't double-handle the same events.
	private val processMutex = Mutex()

	/** Process any unconsumed events for the points module. */
	suspend fun processUnconsumed() = processMutex.withLock {
		if (trackingStartupGate.reconcile() !is com.adsamcik.tracker.shared.base.startup.TrackingStartupResult.Ready) return@withLock
		while (true) {
			val generation = trackingStartupGate.currentGeneration
			val more = trackingStartupGate.withReadyGenerationOperation(generation) {
				val batch = domainEventRepository.getUnconsumedBatchWithIds(
				consumerId = CONSUMER_ID,
				limit = DomainEventRepository.DEFAULT_UNCONSUMED_BATCH_SIZE,
			)
				if (batch.isEmpty()) return@withReadyGenerationOperation false

			batch.forEach { handleEvent(it.event) }
			val last = batch.last()
			domainEventRepository.markBatchConsumed(
				consumerId = CONSUMER_ID,
				upToTimestamp = last.event.timestampMs,
				upToEventId = last.persistedId,
			)
				true
			}
			if (more != true) return@withLock
		}
	}

    /** Requeue existing revisioned receipts in finite pages without holding deletion for the full history. */
    suspend fun reconcileSessionEffects() = processMutex.withLock {
        if (trackingStartupGate.reconcile() !is com.adsamcik.tracker.shared.base.startup.TrackingStartupResult.Ready) return@withLock
        val generation = trackingStartupGate.currentGeneration
        var after = 0
        while (true) {
            val page = trackingStartupGate.withReadyGenerationOperation(generation) {
                val state = appDatabaseProvider.get().sourceEvidenceStateDao().get()
                    ?: return@withReadyGenerationOperation emptyList()
                val rows = pointsDatabaseProvider.get().pointsAwardedDao().sessionEffectPage(after, 100)
                for (row in rows) {
                    val parts = row.effectKey?.split(':').orEmpty()
                    val originalEpoch = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size == 3 && parts[0] == "recording" && originalEpoch != null &&
                        state.acceptsRetainedEpoch(originalEpoch)) {
                        parts[2].toLongOrNull()?.takeIf { it > 0 }?.let { enqueue(it, state.collectedDataEpoch) }
                    }
                }
                rows
            } ?: return@withLock
            if (page.size < 100) return@withLock
            after = page.last().id
        }
    }
	private suspend fun handleEvent(event: DomainEvent) {
		when (event) {
			is DomainEvent.SessionEnded -> onSessionEnded(event)
			else -> Unit
		}
	}

	/**
	 * Replaces PointsSessionReceiver.onSessionFinal().
	 * Enqueues PointsWorker with the session ID for points calculation.
	 */
	private suspend fun onSessionEnded(event: DomainEvent.SessionEnded) {
		val sessionId = event.sessionId
		if (sessionId <= 0L) return
		val epoch = appDatabaseProvider.get().sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return
		enqueue(sessionId, epoch)
	}

	private fun enqueue(sessionId: Long, epoch: Long) {

		val workManager = WorkManager.getInstance(context)
		val data = Data.Builder()
			.putLong(ARG_SESSION_ID, sessionId)
			.putLong(PointsWorker.ARG_EPOCH, epoch)
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
			ExistingWorkPolicy.APPEND_OR_REPLACE,
			workRequest,
		)

	}

	companion object {
		const val CONSUMER_ID = "points-module"
		private const val ARG_SESSION_ID = "id"
		const val POINTS_WORK_TAG = "SessionPoints"

		private fun uniqueWorkName(sessionId: Long): String = "$POINTS_WORK_TAG-$sessionId"
	}
}
