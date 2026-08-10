package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExplicitTrackingJoinProjectionTest {
	@Test
	fun `wifi consumer waits for an event-time bracket across reordered admission`() = runTest {
		val context = FakeProjectionContext()
		val projection = ExplicitTrackingJoinProjection(EventTimeJoiner())

		projection.apply(event("before", 1, 9_000, location()), context)
		projection.apply(event("wifi", 2, 10_000, wifi()), context)
		projection.apply(event("after", 3, 11_000, location()), context)
		context.minimumRequiredOrdinal shouldBe 1L

		val frames = context.framesFor("wifi-interpolation")
		frames.last().inputs.getValue(SourceKind.LOCATION).eventIds shouldContainExactly
			listOf("before", "after")
		frames.last().finalization shouldBe JoinFinalization.PROVISIONAL

		projection.apply(event("watermark", 4, 60_000, location()), context)

		val finalFrame = context.framesFor("wifi-interpolation").last()
		finalFrame.inputs.getValue(SourceKind.LOCATION).eventIds shouldContainExactly
			listOf("before", "after")
		finalFrame.finalization shouldBe JoinFinalization.FINAL_COMPLETE
	}

	@Test
	fun `late input after restart appends a correction with provenance`() = runTest {
		val context = FakeProjectionContext()
		ExplicitTrackingJoinProjection(EventTimeJoiner()).apply(
			event("activity", 1, 100_000, activity()),
			context,
		)
		ExplicitTrackingJoinProjection(EventTimeJoiner()).apply(
			event("timeout-progress", 2, 205_000, activity()),
			context,
		)

		context.framesFor("activity-inference")
			.first { it.primaryEventId == "activity" && it.finalization == JoinFinalization.FINAL_COMPLETE }
			.inputs.getValue(SourceKind.LOCATION).result shouldBe JoinInputResult.MISSING

		// A new projection instance proves that candidates, anchors, watermarks, and revision history
		// are recovered from durable join state rather than process memory.
		ExplicitTrackingJoinProjection(EventTimeJoiner()).apply(
			event("late-location", 3, 99_000, location()),
			context,
		)

		val correction = context.framesFor("activity-inference")
			.last { it.primaryEventId == "activity" }
		correction.finalization shouldBe JoinFinalization.CORRECTION
		correction.revision shouldBe 3
		correction.supersedesFrameId shouldBe context.framesFor("activity-inference")
			.filter { it.primaryEventId == "activity" }[1].frameId
		correction.inputs.getValue(SourceKind.LOCATION).eventIds shouldContainExactly listOf("late-location")
		correction.inputs.getValue(SourceKind.LOCATION).ageMs shouldBe 1_000L
	}

	@Test
	fun `future location never leaks into activity before join`() = runTest {
		val context = FakeProjectionContext()
		val projection = ExplicitTrackingJoinProjection(EventTimeJoiner())

		projection.apply(event("future-location", 1, 101_000, location()), context)
		projection.apply(event("activity", 2, 100_000, activity()), context)

		val input = context.framesFor("activity-inference")
			.single { it.primaryEventId == "activity" }
			.inputs.getValue(SourceKind.LOCATION)
		input.result shouldBe JoinInputResult.FUTURE_REJECTED
		input.eventIds shouldBe emptyList()
	}

	@Test
	fun `pressure altitude and sport shadows consume only their declared joined inputs`() = runTest {
		val context = FakeProjectionContext()
		val projection = ExplicitTrackingJoinProjection(EventTimeJoiner())

		projection.apply(event("activity", 1, 95_000, activity()), context)
		projection.apply(event("steps", 2, 105_000, steps()), context)
		projection.apply(event("location", 3, 100_000, location()), context)
		projection.apply(event("pressure", 4, 105_000, pressure()), context)

		val altitude = context.framesFor("pressure-altitude-fusion")
			.last { it.primaryEventId == "location" }
		altitude.inputs.getValue(SourceKind.PRESSURE).eventIds shouldContainExactly listOf("pressure")

		listOf("ski-classifier", "plane-classifier").forEach { consumer ->
			val frame = context.framesFor(consumer).last { it.primaryEventId == "pressure" }
			frame.inputs.getValue(SourceKind.LOCATION).eventIds shouldContainExactly listOf("location")
			frame.inputs.getValue(SourceKind.STEPS).eventIds shouldContainExactly listOf("steps")
			frame.inputs.containsKey(SourceKind.ACTIVITY) shouldBe false
		}

		val sailing = context.framesFor("sailing-classifier")
			.last { it.primaryEventId == "location" }
		sailing.inputs.getValue(SourceKind.ACTIVITY).eventIds shouldContainExactly listOf("activity")
		sailing.inputs.getValue(SourceKind.STEPS).eventIds shouldContainExactly listOf("steps")
		sailing.inputs.containsKey(SourceKind.PRESSURE) shouldBe false
	}

	@Test
	fun `frame codec preserves diagnostic ages revisions and provenance`() {
		val frame = JoinedFrame(
			frameId = "frame",
			joinSpecId = "spec",
			primaryEventId = "primary",
			observationElapsedRealtimeNanos = 10L,
			clockDomainId = "boot",
			inputs = mapOf(
				SourceKind.LOCATION to JoinedInput(listOf("location"), 12L, JoinInputResult.STALE),
			),
			finalization = JoinFinalization.CORRECTION,
			revision = 4,
			supersedesFrameId = "old",
			emittedAtAdmissionOrdinal = 9L,
		)

		JoinedFrameEffectCodec.decode(
			JoinedFrameEffectCodec.encode("consumer", frame),
			JoinedFrameEffectCodec.VERSION,
		) shouldBe JoinedFrameDelivery("consumer", frame)
	}

	private fun location() = LocationFixPayload(
		latitudeDegrees = 50.0,
		longitudeDegrees = 14.0,
		horizontalAccuracyMeters = 5f,
		altitudeMeters = 200.0,
		verticalAccuracyMeters = 4f,
		speedMetersPerSecond = 2f,
		bearingDegrees = null,
		provider = "fused",
	)

	private fun wifi() = WifiResultSnapshotPayload(
		accessPoints = listOf(WifiAccessPointEvidence("token", 2_412, -50)),
		platformTimestampMs = null,
		resultAgeMs = 0L,
	)

	private fun activity() = ActivityRecognitionPayload(
		activityType = 7,
		confidencePercent = 80,
		providerElapsedRealtimeNanos = null,
	)

	private fun pressure() = PressureWindowPayload(
		sampleCount = 2,
		meanHectopascals = 1_000.0,
		sumSquaredDeviations = 0.5,
		minimumHectopascals = 999.5f,
		maximumHectopascals = 1_000.5f,
		windowStartElapsedRealtimeNanos = 95_000L * 1_000_000L,
		windowEndElapsedRealtimeNanos = 105_000L * 1_000_000L,
		firstProviderSequence = 1,
		lastProviderSequence = 2,
	)

	private fun steps() = StepCounterWindowPayload(
		bootClockDomainId = "boot",
		firstCumulativeCount = 100,
		lastCumulativeCount = 110,
		deltaCount = 10,
		windowStartElapsedRealtimeNanos = 90_000L * 1_000_000L,
		windowEndElapsedRealtimeNanos = 105_000L * 1_000_000L,
		firstProviderSequence = 1,
		lastProviderSequence = 2,
		baselineReset = false,
	)

	private fun event(
		id: String,
		ordinal: Long,
		observedMs: Long,
		payload: SourcePayload,
	): AdmittedSourceEvent<SourcePayload> = AdmittedSourceEvent(
		eventId = SourceEventId(id),
		admissionOrdinal = ordinal,
		evidence = SourceEvidenceCandidate(
			providerDedupKey = id,
			logicalTrackingId = LogicalTrackingId("tracking"),
			serviceRunId = null,
			source = payload.source,
			sourceInstanceId = SourceInstanceId("${payload.source.name.lowercase()}-instance"),
			registrationGeneration = 1,
			sourceSequence = ordinal,
			configRevision = 1,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clockDomainId = "boot",
			observedElapsedRealtimeNanos = observedMs * 1_000_000L,
			receivedElapsedRealtimeNanos = observedMs * 1_000_000L,
			wallTimeMs = observedMs,
			wallTimeUncertaintyMs = 0,
			capturedCollectedDataEpoch = 1,
			acquiredAtMs = observedMs,
			quality = SourceQuality(),
			payloadVersion = 1,
			payload = payload,
		),
	)
}

private class FakeProjectionContext : ProjectionContext {
	private val state = mutableMapOf<String, ByteArray>()
	val effects = mutableListOf<ProjectionOutboxEffect>()
	var minimumRequiredOrdinal: Long? = null
		private set

	override suspend fun recordOutbox(effect: ProjectionOutboxEffect) {
		effects += effect
	}

	override suspend fun loadJoinState(key: String): ByteArray? = state[key]

	override suspend fun saveJoinState(
		key: String,
		payload: ByteArray,
		minimumRequiredOrdinal: Long?,
		logicalTrackingId: String?,
		payloadVersion: Int,
	) {
		state[key] = payload
		this.minimumRequiredOrdinal = minimumRequiredOrdinal
	}

	override suspend fun removeJoinState(key: String) {
		state.remove(key)
	}

	fun framesFor(consumerId: String): List<JoinedFrame> = effects
		.filter { it.kind == ExplicitTrackingJoinProjection.OUTBOX_KIND }
		.map { JoinedFrameEffectCodec.decode(it.payload, it.payloadVersion) }
		.filter { it.consumerId == consumerId }
		.map(JoinedFrameDelivery::frame)
}
