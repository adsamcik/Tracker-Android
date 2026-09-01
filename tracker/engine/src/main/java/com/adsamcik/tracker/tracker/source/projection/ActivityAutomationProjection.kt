package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.inject.Inject

class ActivityAutomationProjection @Inject constructor() : Projection {
	override val id: String = ID
	override val version: Int = VERSION
	override val retentionRequired: Boolean = false

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		val evidence = event.evidence
		val stamp = evidence.validatedActivityAutomationStamp() ?: return
		val payload = when (val sourcePayload = evidence.payload) {
			is ActivityRecognitionPayload -> encode(
				event,
				KIND_RECOGNITION,
				sourcePayload.activityType,
				sourcePayload.confidencePercent,
				-1,
				stamp,
			)
			is ActivityTransitionPayload -> encode(
				event,
				KIND_TRANSITION,
				sourcePayload.activityType,
				100,
				sourcePayload.transitionType,
				stamp,
			)
			else -> return
		}
		context.recordOutbox(
			ProjectionOutboxEffect(
				stableId = "$ID:${event.eventId.value}",
				kind = OUTBOX_KIND,
				payloadVersion = PAYLOAD_VERSION,
				payload = payload,
			),
		)
	}

	private fun encode(
		event: AdmittedSourceEvent<out SourcePayload>,
		kind: Int,
		activityType: Int,
		confidence: Int,
		transitionType: Int,
		stamp: ValidatedActivityAutomationStamp,
	): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				val evidence = event.evidence
				output.writeInt(kind)
				output.writeInt(activityType)
				output.writeInt(confidence)
				output.writeInt(transitionType)
				output.writeUTF(evidence.clockDomainId)
				output.writeLong(evidence.observedElapsedRealtimeNanos)
				output.writeLong(evidence.receivedElapsedRealtimeNanos)
				output.writeLong(evidence.registrationGeneration)
				output.writeLong(stamp.authorizationRevision)
				output.writeUTF(stamp.authorizationFingerprint)
				output.writeLong(evidence.capturedCollectedDataEpoch)
				output.writeLong(stamp.automationEpoch)
			}
			bytes.toByteArray()
		}

	companion object {
		const val ID = "activity-automation"
		const val VERSION = 4
		const val OUTBOX_KIND = "activity-automation-v1"
		const val PAYLOAD_VERSION = 4
		const val KIND_RECOGNITION = 1
		const val KIND_TRANSITION = 2
	}
}

private data class ValidatedActivityAutomationStamp(
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val automationEpoch: Long,
)

private fun SourceEvidenceCandidate<out SourcePayload>.validatedActivityAutomationStamp():
	ValidatedActivityAutomationStamp? {
	val automationEpoch = activityAutomationEpoch?.takeIf { it > 0L }
	val authorizationRevision = authorizationRevision?.takeIf { it > 0L }
	val authorizationFingerprint = registrationEligibilityFingerprint
	return when {
		source != SourceKind.ACTIVITY -> null
		registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L -> null
		registrationGeneration <= 0L -> null
		physicalConfigurationFingerprint.isNullOrBlank() -> null
		automationEpoch == null -> null
		authorizationRevision == null -> null
		authorizationFingerprint.isNullOrBlank() -> null
		else -> ValidatedActivityAutomationStamp(
			authorizationRevision,
			authorizationFingerprint,
			automationEpoch,
		)
	}
}
