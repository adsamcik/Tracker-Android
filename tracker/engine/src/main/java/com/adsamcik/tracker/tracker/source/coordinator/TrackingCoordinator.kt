package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.pruneSourceEventStorageBefore
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.ingress.CorruptSourceEventException
import com.adsamcik.tracker.tracker.source.projection.ProjectionDispatcher
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingCoordinator @Inject constructor(
	private val database: AppDatabase,
	private val ingress: DurableSourceIngress,
	private val projections: ProjectionDispatcher,
	private val telemetry: TrackingCoordinatorTelemetry = TrackingCoordinatorTelemetry(),
) {
	private val processBootId = "process-${UUID.randomUUID()}"

	suspend fun drainAvailable(
		ownerToken: String,
		batchSize: Int = DEFAULT_BATCH_SIZE,
	): CoordinatorDrainResult {
		require(ownerToken.isNotBlank())
		require(batchSize > 0)
		val startedAtNanos = System.nanoTime()
		val lease = acquireLease(ownerToken)
		if (lease == null) {
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
				val batch = try {
					ingress.committedBatch(cursor, batchSize)
				} catch (corrupt: CorruptSourceEventException) {
					check(corrupt.admissionOrdinal == cursor + 1L) {
						"Raw quarantine must advance the contiguous source-event cursor"
					}
					projections.quarantineRawEvent(
						admissionOrdinal = corrupt.admissionOrdinal,
						failureCode = "${corrupt.failureCode}_SOURCE_${corrupt.sourceKind}",
					)
					cursor = corrupt.admissionOrdinal
					continue
				}
				if (batch.isEmpty()) break
				for (event in batch) {
					if (!renewLease(lease)) {
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
			releaseLease(lease)
		}
	}

	/** Deletes only old events no active projection or durable join can still require. */
	suspend fun pruneProjectedEvents(createdBeforeMs: Long): Int {
		require(createdBeforeMs >= 0L)
		return database.pruneSourceEventStorageBefore(createdBeforeMs).walEventsDeleted
	}

	private suspend fun acquireLease(ownerToken: String): LifecycleLeaseToken? = database.withTransaction {
		val nowMs = System.currentTimeMillis()
		val nowElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
		val dao = database.sourceProjectionStateDao()
		val inserted = dao.insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = COORDINATOR_LEASE,
				ownerToken = ownerToken,
				acquiredAtMs = nowMs,
				expiresAtMs = nowMs + LEASE_DURATION_MS,
				bootId = processBootId,
				generation = 1,
				acquiredElapsedRealtimeNanos = nowElapsedNanos,
				expiresElapsedRealtimeNanos = nowElapsedNanos + LEASE_DURATION_NANOS,
			),
		)
		if (inserted < 0L && dao.acquireOrRenewLease(
				COORDINATOR_LEASE,
				ownerToken,
				processBootId,
				nowMs,
				nowMs + LEASE_DURATION_MS,
				nowElapsedNanos,
				nowElapsedNanos + LEASE_DURATION_NANOS,
			) != 1
		) return@withTransaction null
		val current = requireNotNull(dao.lease(COORDINATOR_LEASE))
		if (current.ownerToken != ownerToken || current.bootId != processBootId) {
			return@withTransaction null
		}
		LifecycleLeaseToken(COORDINATOR_LEASE, ownerToken, processBootId, current.generation)
	}

	private suspend fun renewLease(lease: LifecycleLeaseToken): Boolean {
		val nowMs = System.currentTimeMillis()
		val nowElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
		val dao = database.sourceProjectionStateDao()
		return dao.acquireOrRenewLease(
			lease.leaseName,
			lease.ownerToken,
			lease.bootId,
			nowMs,
			nowMs + LEASE_DURATION_MS,
			nowElapsedNanos,
			nowElapsedNanos + LEASE_DURATION_NANOS,
		) == 1 && dao.lease(lease.leaseName)?.generation == lease.generation
	}

	private suspend fun releaseLease(lease: LifecycleLeaseToken) {
		database.sourceProjectionStateDao().releaseLease(
			lease.leaseName,
			lease.ownerToken,
			lease.bootId,
			lease.generation,
			System.currentTimeMillis(),
			android.os.SystemClock.elapsedRealtimeNanos(),
		)
	}

	private companion object {
		const val COORDINATOR_LEASE = "tracking-source-coordinator"
		const val LEASE_DURATION_MS = 30_000L
		const val LEASE_DURATION_NANOS = LEASE_DURATION_MS * 1_000_000L
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
