package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val ingress: DurableSourceIngress,
	private val projections: ProjectionDispatcher,
	private val telemetry: TrackingCoordinatorTelemetry = TrackingCoordinatorTelemetry(),
) {
	suspend fun drainAvailable(
		ownerToken: String,
		batchSize: Int = DEFAULT_BATCH_SIZE,
	): CoordinatorDrainResult {
		require(ownerToken.isNotBlank())
		require(batchSize > 0)
		val startedAtNanos = System.nanoTime()
		if (!acquireLease(ownerToken)) {
			telemetry.recordProjectionDrain(0, System.nanoTime() - startedAtNanos)
			return CoordinatorDrainResult.LeaseUnavailable
		}

		var cursor = 0L
		var count = 0
		return try {
			projections.registerAll(1L)
			cursor = database.sourceProjectionStateDao().minimumActiveCheckpoint()
				?: database.sourceEventWalDao().maximumAdmissionOrdinal()
				?: 0L
			while (true) {
				val batch = ingress.committedBatch(cursor, batchSize)
				if (batch.isEmpty()) break
				for (event in batch) {
					if (!renewLease(ownerToken)) {
						return CoordinatorDrainResult.LeaseLost(cursor, count).also {
							telemetry.recordProjectionDrain(count, System.nanoTime() - startedAtNanos)
						}
					}
					val result = projections.dispatch(event)
					if (!result.complete) {
						return CoordinatorDrainResult.ProjectionFailed(
							lastCompletedOrdinal = cursor,
							eventsDispatched = count,
							projectionId = result.failures.first().projectionId,
							failedOrdinal = event.admissionOrdinal,
						).also {
							telemetry.recordProjectionDrain(count, System.nanoTime() - startedAtNanos)
						}
					}
					cursor = event.admissionOrdinal
					count++
				}
				if (batch.size < batchSize) break
			}
			CoordinatorDrainResult.Complete(cursor, count).also {
				telemetry.recordProjectionDrain(count, System.nanoTime() - startedAtNanos)
			}
		} finally {
			releaseLease(ownerToken)
		}
	}

	/** Deletes only old events no active projection or durable join can still require. */
	suspend fun pruneProjectedEvents(createdBeforeMs: Long): Int {
		require(createdBeforeMs >= 0L)
		return database.pruneSourceEventStorageBefore(createdBeforeMs).walEventsDeleted
	}

	private suspend fun acquireLease(ownerToken: String): Boolean = database.withTransaction {
		val now = System.currentTimeMillis()
		val dao = database.sourceProjectionStateDao()
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = COORDINATOR_LEASE,
				ownerToken = ownerToken,
				acquiredAtMs = now,
				expiresAtMs = now + LEASE_DURATION_MS,
			),
		)
		inserted >= 0L || dao.acquireOrRenewLease(
			COORDINATOR_LEASE,
			ownerToken,
			now,
			now + LEASE_DURATION_MS,
		) == 1
	}

	private suspend fun renewLease(ownerToken: String): Boolean {
		val now = System.currentTimeMillis()
		return database.sourceProjectionStateDao().acquireOrRenewLease(
			COORDINATOR_LEASE,
			ownerToken,
			now,
			now + LEASE_DURATION_MS,
		) == 1
	}

	private suspend fun releaseLease(ownerToken: String) {
		database.sourceProjectionStateDao().releaseLease(COORDINATOR_LEASE, ownerToken)
	}

	private companion object {
		const val COORDINATOR_LEASE = "tracking-source-coordinator"
		const val LEASE_DURATION_MS = 30_000L
		const val DEFAULT_BATCH_SIZE = 256
	}
}

sealed interface CoordinatorDrainResult {
	data class Complete(val lastCompletedOrdinal: Long, val eventsDispatched: Int) : CoordinatorDrainResult
	data object LeaseUnavailable : CoordinatorDrainResult
	data class LeaseLost(val lastCompletedOrdinal: Long, val eventsDispatched: Int) : CoordinatorDrainResult
	data class ProjectionFailed(
		val lastCompletedOrdinal: Long,
		val eventsDispatched: Int,
		val projectionId: String,
		val failedOrdinal: Long,
	) : CoordinatorDrainResult
}
