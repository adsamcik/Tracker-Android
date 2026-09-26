package com.adsamcik.tracker.tracker.source.control

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import io.kotest.matchers.shouldBe
import org.junit.Test

class CollectionMotionControllerTest {
	private val subject = CollectionMotionController()

	@Test
	fun `fresh automatic motion start enables full fidelity immediately`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L, initialMotion = true)

		subject.policy.value.motionState shouldBe CollectionMotionState.MOVING
		subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.FULL_FIDELITY
	}

	@Test
	fun `stationary requires spaced corroboration and a dwell before reduced active location`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L)
		subject.onDurableEvidence(candidate(activity(type = STILL, confidence = 90), seconds(1), 1))
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN

		subject.onDurableEvidence(candidate(activity(type = STILL, confidence = 92), seconds(25), 2))
		subject.tick(seconds(100))

		subject.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
		subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.LOW_POWER_SENTINEL
		subject.policy.value.expensiveNetworkScansAllowed shouldBe false
	}

	@Test
	fun `a hardware step immediately restores full fidelity from stationary`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L)
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(1), 1))
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(25), 2))
		subject.tick(seconds(100))

		subject.onDurableEvidence(candidate(steps(delta = 3), seconds(101), 3))

		subject.policy.value.motionState shouldBe CollectionMotionState.PEDESTRIAN
		subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.FULL_FIDELITY
		subject.policy.value.expensiveNetworkScansAllowed shouldBe true
	}

	@Test
	fun `vehicle motion survives a bounded GPS absence but cell handovers cannot renew it`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L)
		subject.onDurableEvidence(candidate(activity(IN_VEHICLE, 90), seconds(1), 1))
		// Strong STILL reports alone cannot turn a moving car into a stop during the tunnel hold.
		subject.onDurableEvidence(candidate(activity(STILL, 90), minutes(2), 2))
		subject.onDurableEvidence(candidate(activity(STILL, 90), minutes(3), 3))
		subject.tick(minutes(9))
		subject.policy.value.motionState shouldBe CollectionMotionState.VEHICLE

		subject.onDurableEvidence(candidate(cell("tower-a"), minutes(9), 4))
		subject.onDurableEvidence(candidate(cell("tower-b"), minutes(9) + seconds(10), 5))
		subject.tick(minutes(18))

		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
		subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.FULL_FIDELITY
	}

	@Test
	fun `one cell reselection does not create a moving false positive`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L)
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(1), 1))
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(25), 2))
		subject.tick(seconds(100))
		subject.onDurableEvidence(candidate(cell("tower-a"), seconds(101), 3))
		subject.onDurableEvidence(candidate(cell("tower-b"), seconds(110), 4))
		subject.onDurableEvidence(candidate(cell("tower-c"), seconds(120), 5))

		subject.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
	}

	@Test
	fun `evidence from another service run cannot alter active policy`() {
		subject.startSession(LOGICAL_ID, RUN_ID, 0L)
		val otherRun = candidate(activity(IN_VEHICLE, 100), seconds(1), 1).copy(
			serviceRunId = ServiceRunId("other-run"),
		)

		subject.onDurableEvidence(otherRun)

		subject.policy.value shouldBe CollectionMotionPolicy.awaitingEvidence()
	}

	@Test
	fun `Location alone confirms stationary then restores moving without assistance`() {
		subject.configureScope("background", "boot", 0L)
		fun fix(at: Long, speed: Float) = candidate(location(speed), seconds(at), at).copy(
			logicalTrackingId = null, serviceRunId = null, locationSpeedAccuracyMetersPerSecond = 0.05f,
		)
		subject.onDurableEvidence(fix(1, 0.05f))
		subject.onDurableEvidence(fix(25, 0.05f))
		subject.tick(seconds(100))
		subject.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
		// A focus join retains the same collection scope, not a fresh inference history.
		subject.configureScope("background", "boot", 0L)
		subject.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
		subject.onDurableEvidence(fix(101, 2f))
		subject.policy.value.motionState shouldBe CollectionMotionState.MOVING
		subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.FULL_FIDELITY
	}

	@Test
	fun `missing or uncertain speed never proves stationary`() {
		listOf(null, 0.5f, Float.NaN).forEach { uncertainty ->
			subject.configureScope("scope-$uncertainty", "boot", 0L)
			listOf(1L, 25L, 95L).forEach { at ->
				subject.onDurableEvidence(candidate(location(0f), seconds(at), at).copy(
					locationSpeedAccuracyMetersPerSecond = uncertainty,
				))
			}
			subject.tick(seconds(100))
			subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
			subject.policy.value.locationStrategy shouldBe LocationCollectionStrategy.FULL_FIDELITY
		}
	}

	@Test
	fun `stillness expires and stale or wrong boot evidence cannot revive it`() {
		subject.configureScope("background", "boot", 0L)
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(1), 1))
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(25), 2))
		subject.tick(seconds(100))
		subject.policy.value.motionState shouldBe CollectionMotionState.STATIONARY
		subject.tick(seconds(146))
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(25), 3).copy(
			receivedElapsedRealtimeNanos = seconds(147),
		))
		subject.onDurableEvidence(candidate(activity(IN_VEHICLE, 99), seconds(148), 4).copy(clockDomainId = "old-boot"))
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
		// A fresh first confirmation after a long gap cannot borrow the expired second vote/dwell.
		subject.onDurableEvidence(candidate(activity(STILL, 90), seconds(200), 5))
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
	}

	@Test
	fun `transient assistance requires current scope and cannot revive a retired scope`() {
		subject.configureScope("background", "boot", seconds(10))
		subject.onMotionAssistance("old", "boot", seconds(11), seconds(11), IN_VEHICLE, 0)
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
		subject.onMotionAssistance("background", "boot", seconds(11), seconds(11), IN_VEHICLE, 0)
		subject.policy.value.motionState shouldBe CollectionMotionState.VEHICLE
		subject.stopScope("background")
		subject.onMotionAssistance("background", "boot", seconds(12), seconds(12), IN_VEHICLE, 0)
		subject.policy.value.motionState shouldBe CollectionMotionState.UNKNOWN
	}

	private fun location(speed: Float?) = LocationFixPayload(50.0, 14.0, 5f, null, null, speed, null, "gps")

	private fun activity(type: Int, confidence: Int) = ActivityRecognitionPayload(type, confidence, null)

	private fun steps(delta: Long) = StepCounterWindowPayload(
		bootClockDomainId = "boot",
		firstCumulativeCount = 100,
		lastCumulativeCount = 100 + delta,
		deltaCount = delta,
		windowStartElapsedRealtimeNanos = 0,
		windowEndElapsedRealtimeNanos = 1,
		firstProviderSequence = 1,
		lastProviderSequence = 2,
		baselineReset = false,
	)

	private fun cell(identifier: String) = CellSnapshotPayload(
		subscriptionId = 1,
		observations = listOf(CellObservationEvidence(identifier, "LTE", true, -80, null)),
		refreshOutcome = CellRefreshOutcome.CALLBACK,
	)

	private fun <T : SourcePayload> candidate(payload: T, at: Long, sequence: Long) = SourceEvidenceCandidate(
		providerDedupKey = null,
		logicalTrackingId = LogicalTrackingId(LOGICAL_ID),
		serviceRunId = ServiceRunId(RUN_ID),
		source = payload.source,
		sourceInstanceId = SourceInstanceId("${payload.source.name.lowercase()}-instance"),
		registrationGeneration = 1,
		sourceSequence = sequence,
		configRevision = 1,
		planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = at,
		receivedElapsedRealtimeNanos = at,
		wallTimeMs = at / 1_000_000,
		wallTimeUncertaintyMs = 0,
		capturedCollectedDataEpoch = 0,
		acquiredAtMs = at / 1_000_000,
		quality = SourceQuality(),
		payloadVersion = 1,
		payload = payload,
	)

	private fun seconds(value: Long) = value * 1_000_000_000L
	private fun minutes(value: Long) = seconds(value * 60)

	private companion object {
		const val LOGICAL_ID = "logical"
		const val RUN_ID = "run"
		const val IN_VEHICLE = StableActivityTypeCode.IN_VEHICLE
		const val STILL = StableActivityTypeCode.STILL
	}
}
