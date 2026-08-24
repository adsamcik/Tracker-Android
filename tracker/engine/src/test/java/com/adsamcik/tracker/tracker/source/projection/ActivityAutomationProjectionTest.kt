package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActivityAutomationProjectionTest {
	private val projection = ActivityAutomationProjection()

	@Test
	fun `capture-only activity cannot create an automatic-control effect`() = runTest {
		val context = RecordingProjectionContext()

		projection.apply(event(SourceBrokerPurpose.MASK_SESSION_CAPTURE), context)

		context.effects shouldHaveSize 0
	}

	@Test
	fun `automatic-control eligibility creates one automatic-control effect`() = runTest {
		val context = RecordingProjectionContext()

		projection.apply(
			event(
				SourceBrokerPurpose.MASK_SESSION_CAPTURE or
					SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			),
			context,
		)

		context.effects shouldHaveSize 1
		val effect = context.effects.single()
		effect.payloadVersion shouldBe ActivityAutomationProjection.PAYLOAD_VERSION
		DataInputStream(ByteArrayInputStream(effect.payload)).use { input ->
			repeat(4) { input.readInt() }
			input.readUTF() shouldBe "boot-1"
			input.readLong() shouldBe 1_000
			input.readLong() shouldBe 1_100
			input.readLong() shouldBe 1
			input.readLong() shouldBe 7
			input.readUTF() shouldBe "eligibility"
			input.readLong() shouldBe 1
			input.readLong() shouldBe 17
			input.available() shouldBe 0
		}
	}

	@Test
	fun `control-authorized callback projects only its automation-selected member`() = runTest {
		val context = RecordingProjectionContext()

		projection.apply(
			event(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, automationEpoch = null),
			context,
		)
		projection.apply(
			event(SourceBrokerPurpose.MASK_CONTROL_AUTOSTART, automationEpoch = 17L),
			context,
		)

		context.effects shouldHaveSize 1
	}

	private fun event(
		purposeMask: Long,
		automationEpoch: Long? = 17L,
	) = AdmittedSourceEvent(
		eventId = SourceEventId("activity-$purposeMask"),
		admissionOrdinal = 1,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = "activity-$purposeMask",
			logicalTrackingId = null,
			serviceRunId = null,
			source = SourceKind.ACTIVITY,
			sourceInstanceId = SourceInstanceId("activity-provider"),
			registrationGeneration = 1,
			authorizationRevision = 7,
			registrationPurposeEligibilityMask = purposeMask,
			registrationEligibilityFingerprint = "eligibility",
			sourceSequence = 1,
			configRevision = 1,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot-1",
			observedElapsedRealtimeNanos = 1_000,
			receivedElapsedRealtimeNanos = 1_100,
			wallTimeMs = 2_000,
			wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 1,
			activityAutomationEpoch = automationEpoch,
			acquiredAtMs = 2_000,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = ActivityRecognitionPayload(1, 80, 1_000),
		),
	)
}

private class RecordingProjectionContext : ProjectionContext {
	val effects = mutableListOf<ProjectionOutboxEffect>()

	override suspend fun recordOutbox(effect: ProjectionOutboxEffect) {
		effects += effect
	}

	override suspend fun loadJoinState(key: String): ByteArray? = null

	override suspend fun saveJoinState(
		key: String,
		payload: ByteArray,
		minimumRequiredOrdinal: Long?,
		logicalTrackingId: String?,
		payloadVersion: Int,
	) = Unit

	override suspend fun removeJoinState(key: String) = Unit
}
