package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryCandidate
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryUnit
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceDeliveryAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import com.adsamcik.tracker.tracker.source.runtime.SensorAdmissionCheckpoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DurableSourceEventSinkFactory private constructor(
	private val ingress: DurableSourceIngress,
	private val deliveryIngress: DurableSourceDeliveryIngress?,
	private val recovery: SourcePipelineRecovery?,
	private val motionController: CollectionMotionController?,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	private val motionEvidenceLock = Any()
	private val latestMotionEvidenceNanos = mutableMapOf<MotionEvidenceStream, Long>()

	@Inject
	constructor(
		ingress: DurableSourceIngress,
		deliveryIngress: DurableSourceDeliveryIngress,
		recovery: SourcePipelineRecovery,
		motionController: CollectionMotionController,
	) : this(ingress, deliveryIngress, recovery, motionController, Unit)

	internal constructor(
		ingress: DurableSourceIngress,
		recovery: SourcePipelineRecovery,
	) : this(ingress, ingress as? DurableSourceDeliveryIngress, recovery, null, Unit)

	internal constructor(
		ingress: DurableSourceIngress,
		recovery: SourcePipelineRecovery,
		motionController: CollectionMotionController,
	) : this(ingress, ingress as? DurableSourceDeliveryIngress, recovery, motionController, Unit)

	internal constructor(
		ingress: DurableSourceIngress,
		deliveryIngress: DurableSourceDeliveryIngress,
		recovery: SourcePipelineRecovery,
	) : this(ingress, deliveryIngress, recovery, null, Unit)

	internal constructor(ingress: DurableSourceIngress) :
		this(ingress, ingress as? DurableSourceDeliveryIngress, null, null, Unit)

	fun forSession(logicalTrackingId: String, serviceRunId: String): SourceEventSink =
		durableSink { candidate -> candidate.withSession(logicalTrackingId, serviceRunId) }

	val unbound: SourceEventSink
		get() = durableSink { it }

	private fun durableSink(
		bind: (SourceEvidenceCandidate<*>) -> SourceEvidenceCandidate<*>,
	): SourceEventSink = object : SourceEventSink {
		override suspend fun admit(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff =
			admitAndDrain(bind(candidate))

		override suspend fun admit(
			candidate: SourceEvidenceCandidate<*>,
			checkpoint: SensorAdmissionCheckpoint,
		): SourceAdmissionHandoff = admitAndDrain(bind(candidate), checkpoint)

		override suspend fun admit(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff =
			admitAndDrain(delivery.mapEvidence(bind))
	}

	private suspend fun admitAndDrain(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff {
		val result = ingress.admit(candidate)
		if (result.isDurable) {
			onDurableMotionEvidence(candidate, result is AdmissionResult.Duplicate)
			requestSourceDrain(candidate.source)
		}
		return result.toHandoff()
	}

	private suspend fun admitAndDrain(
		candidate: SourceEvidenceCandidate<*>,
		checkpoint: SensorAdmissionCheckpoint,
	): SourceAdmissionHandoff {
		val result = ingress.admit(candidate, checkpoint)
		if (result.isDurable) {
			onDurableMotionEvidence(candidate, result is AdmissionResult.Duplicate)
			requestSourceDrain(candidate.source)
		}
		return result.toHandoff()
	}

	private fun onDurableMotionEvidence(
		candidate: SourceEvidenceCandidate<*>,
		duplicate: Boolean,
	) {
		val controller = motionController ?: return
		val stream = MotionEvidenceStream(candidate.source.stableCode)
		synchronized(motionEvidenceLock) {
			val latest = latestMotionEvidenceNanos[stream]
			if (latest != null && (candidate.observedElapsedRealtimeNanos < latest ||
				duplicate && candidate.observedElapsedRealtimeNanos == latest)
			) return
			controller.onDurableEvidence(candidate)
			latestMotionEvidenceNanos[stream] = maxOf(
				latest ?: Long.MIN_VALUE,
				candidate.observedElapsedRealtimeNanos,
			)
		}
	}

	private suspend fun admitAndDrain(delivery: SourceDeliveryCandidate): SourceDeliveryAdmissionHandoff {
		val activeDeliveryIngress = deliveryIngress ?: return SourceDeliveryAdmissionHandoff.TerminalFailure(
			SourceAdmissionFailureCode.INVALID_EVIDENCE,
		)
		val result = activeDeliveryIngress.admit(delivery)
		if (result is DeliveryAdmissionResult.Admitted) {
			val unitsByIndex = delivery.units.associateBy(SourceDeliveryUnit::unitIndex)
			result.units.forEach { admitted ->
				unitsByIndex[admitted.unitIndex]?.let { unit ->
					onDurableMotionEvidence(unit.evidence, duplicate = false)
				}
			}
		}
		if (result.isDurable) {
			delivery.units.asSequence().map { it.evidence.source }.distinct()
				.forEach(::requestSourceDrain)
		}
		return result.toHandoff()
	}

	private fun requestSourceDrain(source: SourceKind) {
		when (source) {
			SourceKind.ACTIVITY -> recovery?.requestCommittedWorkDrain()
			SourceKind.STEPS -> recovery?.requestStepsSessionFactDrain()
			else -> Unit
		}
	}
}

private data class MotionEvidenceStream(
	val sourceKind: Int,
)

private val AdmissionResult.isDurable: Boolean
	get() = this is AdmissionResult.Admitted || this is AdmissionResult.Duplicate

private val DeliveryAdmissionResult.isDurable: Boolean
	get() = this is DeliveryAdmissionResult.Admitted || this is DeliveryAdmissionResult.Duplicate

@Suppress("UNCHECKED_CAST")
private fun SourceEvidenceCandidate<*>.withSession(
	logicalTrackingId: String,
	serviceRunId: String,
): SourceEvidenceCandidate<*> = (this as SourceEvidenceCandidate<SourcePayload>).copy(
	logicalTrackingId = LogicalTrackingId(logicalTrackingId),
	serviceRunId = ServiceRunId(serviceRunId),
)

private fun SourceDeliveryCandidate.mapEvidence(
	transform: (SourceEvidenceCandidate<*>) -> SourceEvidenceCandidate<*>,
): SourceDeliveryCandidate = copy(
	units = units.map { unit ->
		SourceDeliveryUnit(
			unitIndex = unit.unitIndex,
			evidence = transform(unit.evidence),
			observedIntervalStartElapsedRealtimeNanos = unit.observedIntervalStartElapsedRealtimeNanos,
		)
	},
)

private fun AdmissionResult.toHandoff(): SourceAdmissionHandoff = when (this) {
	is AdmissionResult.Admitted -> SourceAdmissionHandoff.Durable(admissionOrdinal)
	is AdmissionResult.Duplicate -> SourceAdmissionHandoff.Duplicate(existingAdmissionOrdinal)
	is AdmissionResult.RetryableFailure -> SourceAdmissionHandoff.RetryableFailure(code.toRuntimeCode())
	is AdmissionResult.PermanentFailure -> SourceAdmissionHandoff.TerminalFailure(code.toRuntimeCode())
}

private fun DeliveryAdmissionResult.toHandoff(): SourceDeliveryAdmissionHandoff = when (this) {
	is DeliveryAdmissionResult.Admitted -> SourceDeliveryAdmissionHandoff.Durable(
		units.sortedBy(DeliveryAdmissionResult.AdmittedUnit::unitIndex)
			.map(DeliveryAdmissionResult.AdmittedUnit::admissionOrdinal),
	)
	is DeliveryAdmissionResult.Duplicate -> SourceDeliveryAdmissionHandoff.Duplicate(
		units.sortedBy(DeliveryAdmissionResult.AdmittedUnit::unitIndex)
			.map(DeliveryAdmissionResult.AdmittedUnit::admissionOrdinal),
	)
	is DeliveryAdmissionResult.RetryableFailure ->
		SourceDeliveryAdmissionHandoff.RetryableFailure(code.toRuntimeCode())
	is DeliveryAdmissionResult.PermanentFailure ->
		SourceDeliveryAdmissionHandoff.TerminalFailure(code.toRuntimeCode())
}

private fun AdmissionFailureCode.toRuntimeCode(): SourceAdmissionFailureCode = when (this) {
	AdmissionFailureCode.STALE_REGISTRATION_GENERATION ->
		SourceAdmissionFailureCode.STALE_REGISTRATION_GENERATION
	AdmissionFailureCode.AUTHORIZATION_BOUNDARY_SPLIT_REQUIRED ->
		SourceAdmissionFailureCode.INVALID_EVIDENCE
	AdmissionFailureCode.INVALID_OBSERVED_TIME -> SourceAdmissionFailureCode.INVALID_EVIDENCE
	AdmissionFailureCode.STALE_OBSERVATION -> SourceAdmissionFailureCode.INVALID_EVIDENCE
	AdmissionFailureCode.STALE_SOURCE_POLICY -> SourceAdmissionFailureCode.SOURCE_POLICY_STALE
	AdmissionFailureCode.CAPTURE_ADMISSION_CLOSED -> SourceAdmissionFailureCode.SOURCE_POLICY_STALE
	AdmissionFailureCode.STALE_SESSION_MANIFEST -> SourceAdmissionFailureCode.SOURCE_POLICY_STALE
	AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH -> SourceAdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH
	AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY -> SourceAdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
	AdmissionFailureCode.UNSUPPORTED_PAYLOAD -> SourceAdmissionFailureCode.CODEC_UNSUPPORTED
	AdmissionFailureCode.STORAGE_UNAVAILABLE,
	AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
	AdmissionFailureCode.STARTUP_RECOVERY_NOT_READY,
	-> SourceAdmissionFailureCode.STORAGE_UNAVAILABLE
	AdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED ->
		SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED
	AdmissionFailureCode.IDENTITY_COLLISION -> SourceAdmissionFailureCode.INVALID_EVIDENCE
}
