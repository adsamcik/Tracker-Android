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
	override val version: Int = 1

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		if (event.evidence.registrationPurposeEligibilityMask and
			SourceBrokerPurpose.MASK_CONTROL_AUTOSTART == 0L
		) return
		val payload = when (val sourcePayload = event.evidence.payload) {
			is ActivityRecognitionPayload -> encode(
				KIND_RECOGNITION,
				sourcePayload.activityType,
				sourcePayload.confidencePercent,
				-1,
			)
			is ActivityTransitionPayload -> encode(
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
				payloadVersion = 1,
				payload = payload,
			),
		)
	}

	private fun encode(kind: Int, activityType: Int, confidence: Int, transitionType: Int): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(kind)
				output.writeInt(activityType)
				output.writeInt(confidence)
				output.writeInt(transitionType)
			}
			bytes.toByteArray()
		}

	companion object {
		const val ID = "activity-automation"
		const val OUTBOX_KIND = "activity-automation-v1"
		const val KIND_RECOGNITION = 1
		const val KIND_TRANSITION = 2
	}
}
