package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.liveSourceProjectionActivationOrdinal
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceIngress
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Projects only Activity evidence needed by the currently executing Android callback.
 *
 * The global recovery coordinator remains responsible for legacy recovery and every other source.
 * This lane reads Activity WAL rows in source order, advances over unrelated global ordinals, and
 * therefore cannot spend the callback's short start exemption on an unrelated source or projector.
 */
@Singleton
class ActivityAutomationProjectionLane @Inject constructor(
	private val database: AppDatabase,
	private val ingress: DurableSourceIngress,
	private val projections: ProjectionDispatcher,
) {
	private val mutex = Mutex()

	/** Drains the Activity-local lane through the current durable WAL high-water mark. */
	suspend fun drainAvailable(): CoordinatorDrainResult {
		val targetAdmissionOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal()
			?: return CoordinatorDrainResult.Complete(0L, 0)
		return drainThrough(targetAdmissionOrdinal)
	}

	suspend fun drainThrough(targetAdmissionOrdinal: Long): CoordinatorDrainResult = mutex.withLock {
		require(targetAdmissionOrdinal > 0L)
		projections.registerProjection(
			projectionId = ActivityAutomationProjection.ID,
			projectionVersion = ActivityAutomationProjection.VERSION,
			activationOrdinal = database.liveSourceProjectionActivationOrdinal(),
		)
		var checkpoint = requireNotNull(
			database.sourceProjectionStateDao().checkpoint(
				ActivityAutomationProjection.ID,
				ActivityAutomationProjection.VERSION,
			),
		).contiguousAdmissionOrdinal
		var dispatched = 0
		while (checkpoint < targetAdmissionOrdinal) {
			val batch = ingress.committedSourceBatch(
				source = SourceKind.ACTIVITY,
				afterOrdinal = checkpoint,
				throughOrdinal = targetAdmissionOrdinal,
				limit = BATCH_SIZE,
			)
			if (batch.isEmpty()) {
				checkpoint = projections.advanceProjectionAcrossIrrelevantOrdinals(
					projectionId = ActivityAutomationProjection.ID,
					projectionVersion = ActivityAutomationProjection.VERSION,
					throughOrdinal = targetAdmissionOrdinal,
				)
				break
			}
			for (event in batch) {
				check(event.evidence.source == SourceKind.ACTIVITY) {
					"Activity projection lane received ${event.evidence.source}"
				}
				val result = projections.dispatchProjectionWithSourceOrdinalGap(
					projectionId = ActivityAutomationProjection.ID,
					projectionVersion = ActivityAutomationProjection.VERSION,
					event = event,
				)
				if (!result.complete) {
					val durableCheckpoint = requireNotNull(
						database.sourceProjectionStateDao().checkpoint(
							ActivityAutomationProjection.ID,
							ActivityAutomationProjection.VERSION,
						),
					).contiguousAdmissionOrdinal
					val failure = result.failures.first()
					return@withLock CoordinatorDrainResult.ProjectionFailed(
						lastCompletedOrdinal = durableCheckpoint,
						eventsDispatched = dispatched,
						projectionId = failure.projectionId,
						failedOrdinal = failure.admissionOrdinal,
					)
				}
				checkpoint = maxOf(checkpoint, event.admissionOrdinal)
				dispatched++
			}
		}
		CoordinatorDrainResult.Complete(checkpoint, dispatched)
	}

	private companion object {
		const val BATCH_SIZE = 64
	}
}
