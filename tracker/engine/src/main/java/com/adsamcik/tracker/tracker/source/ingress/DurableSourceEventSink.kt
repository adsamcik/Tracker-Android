package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionFailureCode
import com.adsamcik.tracker.tracker.source.runtime.SourceAdmissionHandoff
import com.adsamcik.tracker.tracker.source.runtime.SourceEventSink
import dev.tracebox.Tracebox
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DurableSourceEventSinkFactory private constructor(
	private val ingress: DurableSourceIngress,
	private val recovery: SourcePipelineRecovery?,
	private val motionController: CollectionMotionController?,
	@Suppress("UNUSED_PARAMETER") constructionMarker: Unit,
) {
	@Inject
	constructor(
		ingress: DurableSourceIngress,
		recovery: SourcePipelineRecovery,
		motionController: CollectionMotionController,
	) : this(ingress, recovery, motionController, Unit)

	internal constructor(
		ingress: DurableSourceIngress,
		recovery: SourcePipelineRecovery,
	) : this(ingress, recovery, null, Unit)

	internal constructor(ingress: DurableSourceIngress) : this(ingress, null, null, Unit)

	fun forSession(logicalTrackingId: String, serviceRunId: String): SourceEventSink = SourceEventSink { candidate ->
		admitAndDrain(candidate.withSession(logicalTrackingId, serviceRunId))
	}

	val unbound: SourceEventSink
		get() = SourceEventSink(::admitAndDrain)

	private suspend fun admitAndDrain(candidate: SourceEvidenceCandidate<*>): SourceAdmissionHandoff {
		val result = ingress.admit(candidate)
		if (result.isDurable) {
			motionController?.onDurableEvidence(candidate)
			try {
				val recovered = recovery?.drainCommittedWork()
				if (recovered != null && recovered.drain !is com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult.Complete) {
					Tracebox.log.warn("Durable source projection recovery was deferred")
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Throwable) {
				Tracebox.log.error(error, "Durable source projection recovery failed")
			}
		}
		return result.toHandoff()
	}
}

private val AdmissionResult.isDurable: Boolean
	get() = this is AdmissionResult.Admitted || this is AdmissionResult.Duplicate

@Suppress("UNCHECKED_CAST")
private fun SourceEvidenceCandidate<*>.withSession(
	logicalTrackingId: String,
	serviceRunId: String,
): SourceEvidenceCandidate<*> = (this as SourceEvidenceCandidate<SourcePayload>).copy(
	logicalTrackingId = LogicalTrackingId(logicalTrackingId),
	serviceRunId = ServiceRunId(serviceRunId),
)

private fun AdmissionResult.toHandoff(): SourceAdmissionHandoff = when (this) {
	is AdmissionResult.Admitted -> SourceAdmissionHandoff.Durable(admissionOrdinal)
	is AdmissionResult.Duplicate -> SourceAdmissionHandoff.Duplicate(existingAdmissionOrdinal)
	is AdmissionResult.RetryableFailure -> SourceAdmissionHandoff.RetryableFailure(code.toRuntimeCode())
	is AdmissionResult.PermanentFailure -> SourceAdmissionHandoff.TerminalFailure(code.toRuntimeCode())
}

private fun AdmissionFailureCode.toRuntimeCode(): SourceAdmissionFailureCode = when (this) {
	AdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH -> SourceAdmissionFailureCode.STALE_COLLECTED_DATA_EPOCH
	AdmissionFailureCode.BEFORE_RETENTION_BOUNDARY -> SourceAdmissionFailureCode.BEFORE_RETENTION_BOUNDARY
	AdmissionFailureCode.UNSUPPORTED_PAYLOAD -> SourceAdmissionFailureCode.CODEC_UNSUPPORTED
	AdmissionFailureCode.STORAGE_UNAVAILABLE,
	AdmissionFailureCode.LIFECYCLE_BARRIER_IN_PROGRESS,
	-> SourceAdmissionFailureCode.STORAGE_UNAVAILABLE
	AdmissionFailureCode.IDENTITY_COLLISION -> SourceAdmissionFailureCode.INVALID_EVIDENCE
}
