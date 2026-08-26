package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.tracker.source.ingress.DurableSourceEventSinkFactory
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SharedStepSourceControllerTest {
	private val physical = mockk<StepSourceRuntime>()
	private val broker = mockk<SourceBroker>()
	private val sinkFactory = mockk<DurableSourceEventSinkFactory>()
	private val boot = mockk<BootClockDomainProvider>()
	private val unboundSink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }
	private lateinit var subject: SharedStepSourceController

	@Before
	fun setUp() {
		every { physical.capabilities } returns MutableStateFlow(
			SourceCapabilities(true, true, true, 64, 0L),
		)
		every { sinkFactory.unbound } returns unboundSink
		every { boot.current() } returns "boot-1"
		coEvery { physical.refreshCompatible(any(), unboundSink) } returns null
		coEvery { physical.reconfigure(any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(firstArg()))
		}
		coEvery { physical.close() } returns Unit
		subject = SharedStepSourceController(physical, broker, sinkFactory, boot)
	}

	@Test
	fun `missing explicit Steps control epoch stays disabled and registers nothing`() = runTest {
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns emptyList()

		assertFalse(subject.reconcileAutomaticControl(enabled = true))

		coVerify(exactly = 1) {
			broker.replaceAutomaticControlDemand(any(), SourceKind.STEPS, false, any(), any(), any(), any(), any())
		}
		coVerify(exactly = 0) { physical.reconfigure(any(), any()) }
		coVerify(exactly = 1) { physical.close() }
	}

	@Test
	fun `stale control demand is ignored before during and after session capture`() = runTest {
		val control = demand(SourceBrokerPurpose.CONTROL_AUTOSTART, "app:automatic-start:steps")
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.replaceAutomaticControlDemand(any(), any(), false, any(), any(), any(), any(), any()) } returns null
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returnsMany listOf(
			listOf(control),
			listOf(control, capture),
			listOf(control),
			listOf(control),
		)
		val sessionPlan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		coEvery { physical.refreshCompatible(any(), unboundSink) } returns null
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.quiesce(cutoff) } returns completeAck()

		assertFalse(subject.reconcileAutomaticControl(enabled = true))
		assertIs<SourceStartResult.Started>(subject.start(sessionPlan, mockk()))
		assertIs<SourceStopAck>(subject.quiesce(cutoff))
		subject.close()

		coVerify(exactly = 1) { physical.reconfigure(any(), unboundSink) }
		coVerify(exactly = 1) { physical.refreshCompatible(any(), unboundSink) }
		coVerify(exactly = 0) { physical.sharedCutoff(cutoff) }
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
		coVerify(exactly = 2) { physical.close() }
		coVerify(exactly = 0) { physical.reconfigure(any(), match { it !== unboundSink }) }
	}

	@Test
	fun `session disable returns the physical generation stop acknowledgement`() = runTest {
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns listOf(capture)
		coEvery { physical.reconfigure(any(), unboundSink) } answers {
			val plan = firstArg<StepsPlan>()
			SourceApplyResult.Applied(
				applied(plan),
				stopAck = completeAck().takeIf { !plan.enabled },
			)
		}
		val enabled = StepsPlan(9L, true, 5_000L, 15_000L, false)

		assertIs<SourceStartResult.Started>(subject.start(enabled, mockk()))
		val disabled = assertIs<SourceApplyResult.Applied>(
			subject.reconfigure(enabled.copy(revision = 10L, enabled = false), mockk()),
		)

		assertEquals(1L, disabled.stopAck?.registrationGeneration)
		coVerify(exactly = 1) {
			physical.reconfigure(match { !it.enabled && it.revision == 10L }, unboundSink)
		}
	}

	@Test
	fun `failed terminal retirement never immediately restarts the remaining control join`() = runTest {
		val control = demand(SourceBrokerPurpose.CONTROL_AUTOSTART, "app:automatic-start:steps")
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returnsMany listOf(
			listOf(control, capture),
			listOf(control),
		)
		val sessionPlan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		coEvery { physical.refreshCompatible(any(), unboundSink) } returns
			SourceApplyResult.Applied(applied(sessionPlan))
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.quiesce(cutoff) } returns providerFailedAck()

		assertIs<SourceStartResult.Started>(subject.start(sessionPlan, mockk()))
		val stopped = subject.quiesce(cutoff)

		assertEquals(SourceStopStatus.PROVIDER_FAILED, stopped.status)
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
		coVerify(exactly = 0) { physical.reconfigure(any(), unboundSink) }
	}

	@Test
	fun `compatible successor claim fences stale logical shutdown`() = runTest {
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns listOf(capture)
		val predecessorPlan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		val successorPlan = predecessorPlan.copy(revision = 10L)
		val predecessor = runtimeClaim("shared-steps-predecessor")
		val successor = runtimeClaim("shared-steps-successor")
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.reconfigure(predecessor, any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.reconfigure(successor, any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.shutdownIfOwned(predecessor, cutoff) } returns OwnedSourceShutdown.NotOwned
		coEvery { physical.quiesce(cutoff) } returns completeAck()

		assertIs<SourceStartResult.Started>(subject.start(predecessor, predecessorPlan, mockk()))
		assertIs<SourceApplyResult.Applied>(subject.reconfigure(successor, successorPlan, mockk()))
		assertEquals(OwnedSourceShutdown.NotOwned, subject.shutdownIfOwned(predecessor, cutoff))
		coVerify(exactly = 0) { physical.quiesce(cutoff) }

		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(successor, cutoff))
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
	}

	@Test
	fun `failed successor before publication preserves predecessor logical claim`() = runTest {
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns listOf(capture)
		val predecessorPlan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		val successorPlan = predecessorPlan.copy(revision = 10L)
		val predecessor = runtimeClaim("shared-steps-predecessor")
		val successor = runtimeClaim("shared-steps-successor")
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.reconfigure(predecessor, any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.reconfigure(successor, any(), unboundSink) } answers {
			SourceApplyResult.Failed(applied(secondArg()), retryable = true)
		}
		coEvery { physical.shutdownIfOwned(successor, cutoff) } returns OwnedSourceShutdown.NotOwned
		coEvery { physical.quiesce(cutoff) } returns completeAck()

		assertIs<SourceStartResult.Started>(subject.start(predecessor, predecessorPlan, mockk()))
		assertIs<SourceApplyResult.Failed>(subject.reconfigure(successor, successorPlan, mockk()))
		assertEquals(OwnedSourceShutdown.NotOwned, subject.shutdownIfOwned(successor, cutoff))
		coVerify(exactly = 0) { physical.quiesce(cutoff) }

		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(predecessor, cutoff))
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
	}

	@Test
	fun `partially published failed successor remains physically cleanable by exact claim`() = runTest {
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns listOf(capture)
		val predecessorPlan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		val successorPlan = predecessorPlan.copy(revision = 10L)
		val predecessor = runtimeClaim("shared-steps-predecessor")
		val successor = runtimeClaim("shared-steps-successor")
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.reconfigure(predecessor, any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.reconfigure(successor, any(), unboundSink) } answers {
			SourceApplyResult.Failed(
				applied(secondArg()).copy(
					sourceInstanceId = SourceInstanceId("steps-2"),
					registrationGeneration = 2L,
				),
				retryable = true,
			)
		}
		coEvery { physical.shutdownIfOwned(successor, cutoff) } returns OwnedSourceShutdown.Released(
			SourceProviderKey(SourceInstanceId("steps-2"), 2L),
			completeAck().copy(sourceInstanceId = SourceInstanceId("steps-2"), registrationGeneration = 2L),
		)
		coEvery { physical.quiesce(cutoff) } returns completeAck()

		assertIs<SourceStartResult.Started>(subject.start(predecessor, predecessorPlan, mockk()))
		assertIs<SourceApplyResult.Failed>(subject.reconfigure(successor, successorPlan, mockk()))
		val released = assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(successor, cutoff))
		assertEquals(2L, released.provider?.registrationGeneration)
		coVerify(exactly = 0) { physical.quiesce(cutoff) }

		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(predecessor, cutoff))
		coVerify(exactly = 1) { physical.quiesce(cutoff) }
	}

	@Test
	fun `close retains logical claim after incomplete physical retirement`() = runTest {
		val capture = demand(SourceBrokerPurpose.SESSION_CAPTURE, "session:logical-1")
		coEvery { broker.authorizationDemands(SourceKind.STEPS) } returns listOf(capture)
		val plan = StepsPlan(9L, true, 5_000L, 15_000L, false)
		val claim = runtimeClaim("shared-steps-owner")
		val cutoff = SessionCutoff("logical-1", 900L, 10L, Long.MAX_VALUE)
		coEvery { physical.reconfigure(claim, any(), unboundSink) } answers {
			SourceApplyResult.Applied(applied(secondArg()))
		}
		coEvery { physical.quiesce(cutoff) } returnsMany listOf(providerFailedAck(), completeAck())

		assertIs<SourceStartResult.Started>(subject.start(claim, plan, mockk()))
		assertEquals(SourceStopStatus.PROVIDER_FAILED, subject.quiesce(cutoff).status)
		subject.close()

		assertIs<OwnedSourceShutdown.Released>(subject.shutdownIfOwned(claim, cutoff))
		coVerify(exactly = 2) { physical.quiesce(cutoff) }
		coVerify(exactly = 1) { physical.close() }
	}

	private fun demand(purpose: String, consumer: String) = SourceDemandEntity(
		demandId = "demand-$purpose",
		consumerId = consumer,
		sourceKind = SourceKind.STEPS.stableCode,
		purpose = purpose,
		logicalTrackingId = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) "logical-1" else null,
		serviceRunId = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) "run-1" else null,
		manifestRevision = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) 1L else null,
		lifecycleLeaseGeneration = if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) 1L else null,
		sourcePolicyRevision = 2L,
		consentEpoch = 3L,
		persistenceEligible = purpose == SourceBrokerPurpose.SESSION_CAPTURE,
		qosCode = 0,
		maximumAgeMs = 30_000L,
		desiredLatencyMs = 5_000L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1L,
		requestedAtMs = 1L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun applied(plan: StepsPlan) = AppliedSourcePlan(
		desiredRevision = plan.revision,
		appliedRevision = plan.revision,
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps-1"),
		registrationGeneration = 1L,
		appliedAtElapsedRealtimeNanos = 1L,
		status = SourceApplyStatus.APPLIED,
	)

	private fun completeAck() = SourceStopAck(
		source = SourceKind.STEPS,
		sourceInstanceId = SourceInstanceId("steps-1"),
		registrationGeneration = 1L,
		appliedRevision = 2L,
		callbackEntryBarrierSequence = 2L,
		lastDurablyAdmittedSequence = 2L,
		lastAdmissionOrdinal = 2L,
		failedAdmissionCount = 0L,
		unresolvedSequenceStart = null,
		unresolvedSequenceEndInclusive = null,
		registrationRemovalOutcome = RegistrationRemovalOutcome.NOT_REGISTERED,
		providerFlushOutcome = ProviderFlushOutcome.COMPLETE,
		providerCoverage = ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE,
		appDrainComplete = true,
		status = SourceStopStatus.COMPLETE,
	)

	private fun providerFailedAck() = completeAck().copy(
		registrationRemovalOutcome = RegistrationRemovalOutcome.FAILED,
		status = SourceStopStatus.PROVIDER_FAILED,
	)

	private fun runtimeClaim(actionId: String) = SourceRuntimeClaim(
		source = SourceKind.STEPS,
		actionId = actionId,
		attemptCount = 1,
		leaseGeneration = 1L,
		logicalTrackingId = "logical-1",
		serviceRunId = "run-1",
	)
}
