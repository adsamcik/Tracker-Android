package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.inject.Inject

class ActivityAutomationProjection @Inject constructor() : Projection {
	override val id: String = ID
	override val version: Int = VERSION

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		if (event.evidence.registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L
		) return
		if (event.evidence.activityAutomationEpoch == null) return
		val payload = when (val sourcePayload = event.evidence.payload) {
			is ActivityRecognitionPayload -> encode(
				event,
				KIND_RECOGNITION,
				sourcePayload.activityType,
				sourcePayload.confidencePercent,
				-1,
			)
			is ActivityTransitionPayload -> encode(
				event,
				KIND_TRANSITION,
				sourcePayload.activityType,
				100,
				sourcePayload.transitionType,
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
				output.writeLong(requireNotNull(evidence.authorizationRevision))
				output.writeUTF(requireNotNull(evidence.registrationEligibilityFingerprint))
				output.writeLong(evidence.capturedCollectedDataEpoch)
				output.writeLong(requireNotNull(evidence.activityAutomationEpoch))
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
