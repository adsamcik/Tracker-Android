package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.ProviderCoverage
import com.adsamcik.tracker.tracker.source.runtime.ProviderFlushOutcome
import com.adsamcik.tracker.tracker.source.runtime.RegistrationRemovalOutcome
import com.adsamcik.tracker.tracker.source.runtime.SourceApplyResult
import com.adsamcik.tracker.tracker.source.runtime.SourceStopAck
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import io.kotest.matchers.shouldBe
import org.junit.Test

class DurableLifecycleTest {
	@Test
	fun `disabled apply without provider retirement evidence remains cleanup required`() {
		val execution = SourceApplyResult.Applied(disabledSteps()).toExecution(desiredStarted = false)

		execution.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED
		execution.failureCode shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
		execution.retryTrigger shouldBe "RUNTIME_CLEANUP_RETRY"
	}

	@Test
	fun `unobservable removal cannot satisfy disabled apply`() {
		val execution = SourceApplyResult.Applied(
			disabledSteps(),
			stopAck(RegistrationRemovalOutcome.UNOBSERVABLE),
		).toExecution(desiredStarted = false)

		execution.status shouldBe LifecycleActionStatus.CLEANUP_REQUIRED
		execution.failureCode shouldBe SOURCE_RUNTIME_CLEANUP_PENDING
	}

	@Test
	fun `verified provider removal satisfies disabled apply`() {
		val execution = SourceApplyResult.Applied(
			disabledSteps(),
			stopAck(RegistrationRemovalOutcome.REMOVED),
		).toExecution(desiredStarted = false)

		execution.status shouldBe LifecycleActionStatus.STOP_ACCEPTED
		execution.failureCode shouldBe null
	}

	private fun disabledSteps() = AppliedSourcePlan(
		desiredRevision = 2L,
		appliedRevision = 2L,
		source = SourceKind.STEPS,
		sourceInstanceId = null,
		registrationGeneration = null,
		appliedAtElapsedRealtimeNanos = 2_000_000L,
		status = SourceApplyStatus.APPLIED,
	)

	private fun stopAck(removal: RegistrationRemovalOutcome) = SourceStopAck(
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps-instance"),
		registrationGeneration = 1L,
		appliedRevision = 1L,
		callbackEntryBarrierSequence = 0L,
		lastDurablyAdmittedSequence = null,
		lastAdmissionOrdinal = null,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = removal,
		providerFlushOutcome = ProviderFlushOutcome.NOT_SUPPORTED,
		providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
		appDrainComplete = true,
		status = SourceStopStatus.COMPLETE,
		logicalTrackingId = "logical-1",
		serviceRunId = "run-1",
	)
}
