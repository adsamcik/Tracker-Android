package com.adsamcik.tracker.tracker.source.ingress

import android.content.Context
import com.adsamcik.tracker.activity.api.ingress.ActivityDurableSelection
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressResult
import com.adsamcik.tracker.activity.api.ingress.ActivityIngressStartContext
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEventIngress
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.shared.base.startup.TrackingAdmissionStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationDrainResult
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Durable atomic-delivery adapter for the app-scoped Activity callback PendingIntent. */
@Singleton
class RoomActivityRecognitionEventIngress @Inject constructor(
	private val deliveryFactory: ActivitySourceDeliveryFactory,
	private val trackingStartupGateProvider: Provider<TrackingStartupGate>,
	private val automationEpochAuthority: ActivityAutomationEpochAuthority,
	private val deliveryIngress: DurableSourceDeliveryIngress,
	private val committedIngress: DurableSourceIngress,
	private val sourcePipelineRecovery: SourcePipelineRecovery,
	private val motionController: CollectionMotionController,
	@ApplicationContext private val context: Context,
) : ActivityRecognitionEventIngress {
	override suspend fun admit(batch: ActivityRecognitionEvidenceBatch): ActivityIngressResult {
		if (batch.eventCount == 0) return ActivityIngressResult.durable(0, 0)
		val capturedIdentity = batch.registrationIdentity
			?: return ActivityIngressResult.rejected(0, 0, "MISSING_REGISTRATION_IDENTITY")
		val startup = runCatchingNonCancellation {
			trackingStartupGateProvider.get().reconcileAdmission()
		}.getOrElse { failure ->
			return ActivityIngressResult.retryable(0, 0, failure.failureCode())
		}
		if (startup !is TrackingAdmissionStartupResult.Ready) {
			return ActivityIngressResult.retryable(0, 0, STARTUP_RECOVERY_NOT_READY)
		}
		val automationAuthority = runCatchingNonCancellation {
			automationEpochAuthority.epochForCallbackAdmission()
		}.getOrElse { failure ->
			return ActivityIngressResult.retryable(0, 0, failure.failureCode())
		}
		val delivery = runCatchingNonCancellation {
			deliveryFactory.create(batch, capturedIdentity, automationAuthority)
		}.getOrElse { failure ->
			return if (failure is IllegalArgumentException) {
				ActivityIngressResult.rejected(0, 0, INVALID_PROVIDER_BATCH)
			} else {
				ActivityIngressResult.retryable(0, 0, failure.failureCode())
			}
		}
		val admission = runCatchingNonCancellation {
			deliveryIngress.admit(delivery.candidate)
		}.getOrElse { failure ->
			return ActivityIngressResult.retryable(0, 0, failure.failureCode())
		}
		return when (admission) {
			is DeliveryAdmissionResult.Admitted -> completeDurableAdmission(
				delivery,
				admission.units,
				batch.startContext,
				admittedCount = admission.units.size,
				duplicateCount = 0,
			)
			is DeliveryAdmissionResult.Duplicate -> completeDurableAdmission(
				delivery,
				admission.units,
				batch.startContext,
				admittedCount = 0,
				duplicateCount = admission.units.size,
				publishNewEffects = false,
			)
			is DeliveryAdmissionResult.RetryableFailure -> ActivityIngressResult.retryable(
				0,
				0,
				admission.code.name,
			)
			is DeliveryAdmissionResult.PermanentFailure -> ActivityIngressResult.rejected(
				0,
				0,
				admission.code.name,
			)
		}
	}

	private suspend fun completeDurableAdmission(
		delivery: ActivitySourceDelivery,
		units: List<DeliveryAdmissionResult.AdmittedUnit>,
		startContext: ActivityIngressStartContext,
		admittedCount: Int,
		duplicateCount: Int,
		publishNewEffects: Boolean = true,
	): ActivityIngressResult {
		val orderedUnits = units.sortedBy(DeliveryAdmissionResult.AdmittedUnit::unitIndex)
		if (orderedUnits.isEmpty() || admittedCount + duplicateCount != orderedUnits.size ||
			orderedUnits.map { it.unitIndex }.distinct().size != orderedUnits.size ||
			orderedUnits.any { it.unitIndex !in delivery.originalEvents.indices }
		) {
			return ActivityIngressResult.rejected(0, 0, INVALID_DURABLE_SELECTION)
		}
		val completion = runCatchingNonCancellation {
			val loaded = orderedUnits.map { unit -> loadExactCommittedEvent(unit) }
			val targetOrdinal = orderedUnits.maxOf(
				DeliveryAdmissionResult.AdmittedUnit::admissionOrdinal,
			)
			val callbackTransitionOrdinals = loaded
				.filter { event ->
					event.evidence.payload is ActivityTransitionPayload &&
						event.evidence.activityAutomationEpoch != null
				}
				.mapTo(mutableSetOf()) { event -> event.admissionOrdinal }
			val recovery = if (
				callbackTransitionOrdinals.isEmpty() ||
				startContext != ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK
			) {
				sourcePipelineRecovery.drainCommittedWork()
			} else {
				sourcePipelineRecovery.drainCommittedActivityCallbackWork(
					callbackTransitionOrdinals,
					targetOrdinal,
				) {
					BackgroundTrackingApi.initializeAndAwaitActivityAutomationAuthority(context)
				}
			}
			loaded to recovery
		}.getOrElse { failure ->
			return ActivityIngressResult.retryable(
				admittedCount,
				duplicateCount,
				failure.failureCode(),
			)
		}
		val targetOrdinal = orderedUnits.maxOf(DeliveryAdmissionResult.AdmittedUnit::admissionOrdinal)
		val drain = completion.second.drain
		if (drain !is CoordinatorDrainResult.Complete || drain.lastCompletedOrdinal < targetOrdinal) {
			return ActivityIngressResult.retryable(
				admittedCount,
				duplicateCount,
				drain.failureCode(),
			)
		}
		when (completion.second.activityAutomationDrain) {
			is ActivityAutomationDrainResult.Retryable,
			is ActivityAutomationDrainResult.ProjectionDeferred,
			is ActivityAutomationDrainResult.MorePending,
			-> return ActivityIngressResult.retryable(
				admittedCount,
				duplicateCount,
				ACTIVITY_AUTOMATION_EFFECT_PENDING,
			)
			is ActivityAutomationDrainResult.Complete,
			null,
			-> Unit
		}

		if (!publishNewEffects) {
			return ActivityIngressResult.durable(0, duplicateCount)
		}

		// Every exact row is loaded and recovery reached this delivery before transient publication.
		completion.first.forEach { event -> motionController.onDurableEvidence(event.evidence) }
		val recognitionIndexes = mutableSetOf<Int>()
		val transitionIndexes = mutableSetOf<Int>()
		orderedUnits.forEach { unit ->
			when (val original = delivery.originalEvents[unit.unitIndex]) {
				is ActivityOriginalEvent.Recognition -> recognitionIndexes += original.index
				is ActivityOriginalEvent.Transition -> transitionIndexes += original.index
			}
		}
		return ActivityIngressResult.durable(
			admittedCount,
			duplicateCount,
			ActivityDurableSelection(recognitionIndexes, transitionIndexes),
		)
	}

	private suspend fun loadExactCommittedEvent(
		unit: DeliveryAdmissionResult.AdmittedUnit,
	): AdmittedSourceEvent<out SourcePayload> {
		check(unit.admissionOrdinal > 0L) { "Committed Activity admission ordinal must be positive" }
		val event = committedIngress.committedBatch(unit.admissionOrdinal - 1L, 1).singleOrNull()
			?: error("Committed Activity event is unavailable")
		check(event.admissionOrdinal == unit.admissionOrdinal) {
			"Committed Activity admission ordinal does not match"
		}
		check(event.eventId == unit.eventId) { "Committed Activity event ID does not match" }
		check(event.evidence.source == SourceKind.ACTIVITY) { "Committed event is not Activity evidence" }
		return event
	}

	private fun Throwable.failureCode(): String = javaClass.simpleName.ifBlank {
		COMMITTED_EVENT_RELOAD_FAILED
	}

	private fun CoordinatorDrainResult.failureCode(): String = when (this) {
		is CoordinatorDrainResult.Complete -> "PIPELINE_DRAIN_INCOMPLETE"
		CoordinatorDrainResult.LeaseUnavailable -> "PIPELINE_LEASE_UNAVAILABLE"
		is CoordinatorDrainResult.LeaseLost -> "PIPELINE_LEASE_LOST"
		is CoordinatorDrainResult.ProjectionFailed -> "PIPELINE_PROJECTION_FAILED"
	}

	private companion object {
		const val INVALID_PROVIDER_BATCH = "INVALID_ACTIVITY_PROVIDER_BATCH"
		const val STARTUP_RECOVERY_NOT_READY = "STARTUP_RECOVERY_NOT_READY"
		const val INVALID_DURABLE_SELECTION = "INVALID_DURABLE_SELECTION"
		const val COMMITTED_EVENT_RELOAD_FAILED = "COMMITTED_EVENT_RELOAD_FAILED"
		const val ACTIVITY_AUTOMATION_EFFECT_PENDING = "ACTIVITY_AUTOMATION_EFFECT_PENDING"
	}
}
