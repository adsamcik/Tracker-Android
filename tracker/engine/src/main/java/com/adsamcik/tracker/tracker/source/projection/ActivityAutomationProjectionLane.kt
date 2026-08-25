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

	/**
	 * Installs the optional control projection at the current live tail before its provider can
	 * produce a callback. Registration is durable and idempotent, so a crash after this boundary
	 * can safely retry without moving the activation ordinal past newly admitted evidence.
	 */
	suspend fun ensureRegisteredAtLiveTail() = mutex.withLock {
		registerAtLiveTail()
	}

	/** Drains the Activity-local lane through the current durable WAL high-water mark. */
	suspend fun drainAvailable(): CoordinatorDrainResult {
		val targetAdmissionOrdinal = database.sourceEventWalDao().maximumAdmissionOrdinal()
		if (targetAdmissionOrdinal == null) {
			ensureRegisteredAtLiveTail()
			val checkpoint = requireNotNull(
				database.sourceProjectionStateDao().checkpoint(
					ActivityAutomationProjection.ID,
					ActivityAutomationProjection.VERSION,
				),
			).contiguousAdmissionOrdinal
			return CoordinatorDrainResult.Complete(checkpoint, 0)
		}
		return drainThrough(targetAdmissionOrdinal)
	}

	suspend fun drainThrough(targetAdmissionOrdinal: Long): CoordinatorDrainResult = mutex.withLock {
		require(targetAdmissionOrdinal > 0L)
		registerAtLiveTail()
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

	private suspend fun registerAtLiveTail() {
		projections.registerProjection(
			projectionId = ActivityAutomationProjection.ID,
			projectionVersion = ActivityAutomationProjection.VERSION,
			activationOrdinal = database.liveSourceProjectionActivationOrdinal(),
		)
	}

	private companion object {
		const val BATCH_SIZE = 64
	}
}
