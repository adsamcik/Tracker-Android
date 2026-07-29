package com.adsamcik.tracker.tracker.control

import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionApplyOutcome
import com.adsamcik.tracker.stats.api.research.ResearchAcquisitionRecord
import com.adsamcik.tracker.stats.api.research.ResearchTraceIdentity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class ResearchControlTraceRecorderTest {
	@Test
	fun `controller outcomes retain request correlation and expose adapter fallback`() {
		val recorder = ResearchControlTraceRecorder(
			identity = ResearchTraceIdentity("trace", "run", "session"),
		)
		val decision = decisionOutput()
		val requestedCommand = decisionCommand()
		recorder.onDecision(decision)
		recorder.onAcquisitionOutcome(
			ControlAcquisitionApplyResult(
				command = requestedCommand,
				outcome = ControlAcquisitionApplyOutcome.APPLIED,
				applied = requestedCommand.request,
				reason = "PLATFORM_REQUEST_APPLIED",
				eventEpochMs = 11L,
				eventElapsedNanos = 21L,
				clockDomainId = "clock-a",
			),
		)

		val noChangeCommand = requestedCommand.copy(requestId = "logical:8", decisionLedgerSequence = 8L)
		recorder.onAcquisitionOutcome(
			ControlAcquisitionApplyResult(
				command = noChangeCommand,
				outcome = ControlAcquisitionApplyOutcome.NO_CHANGE,
				applied = noChangeCommand.request,
				reason = "PLATFORM_SHAPE_ALREADY_APPLIED",
				eventEpochMs = 12L,
				eventElapsedNanos = 22L,
				clockDomainId = "clock-a",
			),
		)

		val failedCommand = requestedCommand.copy(requestId = "logical:9", decisionLedgerSequence = 9L)
		recorder.onAcquisitionOutcome(
			ControlAcquisitionApplyResult(
				command = failedCommand,
				outcome = ControlAcquisitionApplyOutcome.FAILED,
				reason = "TRIGGER_PREPARATION_FAILED",
				eventEpochMs = 13L,
				eventElapsedNanos = 23L,
				clockDomainId = "clock-a",
			),
		)

		val fallbackCommand = requestedCommand.copy(
			requestId = "logical:7:adapter-probe-deadline",
			request = AcquisitionRequest(
				mode = LocationAcquisitionMode.PASSIVE,
				intervalMs = 60_000L,
				minDistanceMeters = 0,
				reason = "ADAPTER_PROBE_DEADLINE",
			),
			origin = ControlAcquisitionCommandOrigin.ADAPTER_PROBE_DEADLINE,
		)
		recorder.onAcquisitionOutcome(
			ControlAcquisitionApplyResult(
				command = fallbackCommand,
				outcome = ControlAcquisitionApplyOutcome.APPLIED,
				applied = fallbackCommand.request,
				reason = "PLATFORM_REQUEST_APPLIED",
				eventEpochMs = 14L,
				eventElapsedNanos = 24L,
				clockDomainId = "clock-a",
			),
		)

		val acquisitions = recorder.snapshot().mapNotNull { it.record as? ResearchAcquisitionRecord }
		acquisitions.map { it.outcome } shouldContainExactly listOf(
			ResearchAcquisitionApplyOutcome.REQUESTED,
			ResearchAcquisitionApplyOutcome.APPLIED,
			ResearchAcquisitionApplyOutcome.NO_CHANGE,
			ResearchAcquisitionApplyOutcome.FAILED,
			ResearchAcquisitionApplyOutcome.APPLIED,
		)
		acquisitions[0].requestId shouldBe requestedCommand.requestId
		acquisitions[1].requestId shouldBe requestedCommand.requestId
		acquisitions[1].applied?.providerIdentity shouldBe "tracker-controller"
		acquisitions[3].applied shouldBe null
		acquisitions[4].reason shouldBe "ADAPTER_PROBE_DEADLINE:PLATFORM_REQUEST_APPLIED"
		acquisitions[4].desired.providerIdentity shouldBe "controller-adapter"
	}

	private fun decisionOutput(): ControlOutput {
		val request = AcquisitionRequest(
			mode = LocationAcquisitionMode.BALANCED,
			intervalMs = 15_000L,
			minDistanceMeters = 10,
			reason = "MOVING",
		)
		return ControlOutput(
			ledgerSequence = 7L,
			input = ControlInput(
				wallTimeMs = 10L,
				elapsedRealtimeNanos = 20L,
				clockDomainId = "clock-a",
				payload = ControlEvidence.Tick(),
			),
			transitions = listOf(
				ControlTransition(
					kind = ControlTransitionKind.ACQUISITION,
					from = LocationAcquisitionMode.PASSIVE.name,
					to = LocationAcquisitionMode.BALANCED.name,
					reason = request.reason,
				),
			),
			snapshot = TrackingDecisionSnapshot(
				logicalTrackingId = LogicalTrackingId("logical"),
				sessionOrigin = TrackingSessionOrigin.USER,
				lifecycle = LogicalLifecycleState.ACTIVE,
				motion = MotionState.MOVING,
				observability = LocationObservabilityState.AVAILABLE,
				acquisition = request,
				continuity = TrackingContinuity.CONTINUOUS,
			),
		)
	}

	private fun decisionCommand(): ControlAcquisitionCommand = ControlAcquisitionCommand(
		requestId = controlAcquisitionRequestId("logical", 7L),
		logicalTrackingId = "logical",
		decisionLedgerSequence = 7L,
		request = AcquisitionRequest(
			mode = LocationAcquisitionMode.BALANCED,
			intervalMs = 15_000L,
			minDistanceMeters = 10,
			reason = "MOVING",
		),
		decisionEpochMs = 10L,
		decisionElapsedNanos = 20L,
		clockDomainId = "clock-a",
	)
}
