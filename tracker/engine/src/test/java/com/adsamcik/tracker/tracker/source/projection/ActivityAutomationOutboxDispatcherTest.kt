package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourceProjectionStateDao
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryEnvelope
import com.adsamcik.tracker.tracker.api.ActivityAutomationDeliveryResult
import com.adsamcik.tracker.tracker.api.ActivityAutomationStartContext
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActivityAutomationOutboxDispatcherTest {
	private val database = mockk<AppDatabase>()
	private val dao = mockk<SourceProjectionStateDao>()
	private val validator = mockk<ActivityAutomationEffectValidator>()
	private val consumer = mockk<ActivityAutomationEffectConsumer>()
	private val subject = ActivityAutomationOutboxDispatcher(database, validator, consumer)

	init {
		every { database.sourceProjectionStateDao() } returns dao
		coEvery { validator.validate(any()) } returns ActivityAutomationEffectValidation.Eligible(8, 3)
	}

	@Test
	fun `cold unready consumer leaves the oldest effect pending`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(
			effect(
				payload = payload(
					kind = ActivityAutomationProjection.KIND_TRANSITION,
					transition = ActivityTransitionType.ENTER.value,
				),
			),
		)
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.RETRY

		subject.drain() shouldBe 0
		subject.drainRequired.value shouldBe true

		coVerify(exactly = 0) { dao.markOutboxDelivered(any(), any()) }
		coVerify(exactly = 0) { dao.markOutboxTerminal(any(), any(), any()) }
	}

	@Test
	fun `bounded drain consumes every page through durable quiescence`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returnsMany listOf(
			listOf(effect("first", 1), effect("second", 2)),
			listOf(effect("third", 3)),
		)
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.ACCEPTED
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drainToQuiescence(batchSize = 2, maxBatches = 2) shouldBe
			ActivityAutomationDrainResult.Complete(deliveredCount = 3, terminalCount = 0)
		subject.drainRequired.value shouldBe false

		coVerify(exactly = 3) { consumer.deliver(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `bounded drain reports remaining backlog instead of monopolizing caller`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns
			listOf(effect("first", 1), effect("second", 2))
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.ACCEPTED
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drainToQuiescence(batchSize = 2, maxBatches = 1) shouldBe
			ActivityAutomationDrainResult.MorePending(deliveredCount = 2, terminalCount = 0)
		subject.drainRequired.value shouldBe true
	}

	@Test
	fun `poison payload is terminalized without blocking a newer effect`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(
			effect("poison", 1, byteArrayOf(1)),
			effect("valid", 2),
		)
		coEvery { dao.markOutboxTerminal(any(), any(), any()) } returns 1
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.ACCEPTED
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drainToQuiescence(batchSize = 3, maxBatches = 1) shouldBe
			ActivityAutomationDrainResult.Complete(deliveredCount = 1, terminalCount = 1)

		coVerify(exactly = 1) {
			dao.markOutboxTerminal("poison", "INVALID_AUTOMATION_EFFECT_PAYLOAD", any())
		}
		val deliveredEvidence = slot<ActivityAutomationDeliveryEnvelope>()
		coVerify(exactly = 1) {
			consumer.deliver(
				any(),
				capture(deliveredEvidence),
				8,
				3,
				ActivityAutomationStartContext.DURABLE_REPLAY,
			)
		}
		deliveredEvidence.captured.activityType shouldBe DetectedActivityType.WALKING
		deliveredEvidence.captured.admissionOrdinal shouldBe 2L
	}

	@Test
	fun `retry stops the ordered drain before evaluating newer effects`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns
			listOf(effect("first", 1), effect("second", 2))
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.RETRY

		subject.drain() shouldBe 0

		coVerify(exactly = 1) { consumer.deliver(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `current policy suppression is durable and is never marked delivered`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(effect())
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		coEvery { dao.markOutboxTerminal(any(), any(), any()) } returns 1

		subject.drain() shouldBe 0

		coVerify(exactly = 1) {
			dao.markOutboxTerminal(
				"effect",
				"CURRENT_POLICY_SUPPRESSED_AUTOMATION",
				any(),
			)
		}
		coVerify(exactly = 0) { dao.markOutboxDelivered(any(), any()) }
	}

	@Test
	fun `app replay terminalizes an automatic start whose callback context expired`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(
			effect(
				stableId = "expired",
				payload = payload(
					kind = ActivityAutomationProjection.KIND_TRANSITION,
					transition = ActivityTransitionType.ENTER.value,
				),
			),
		)
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
		coEvery { dao.markOutboxTerminal(any(), any(), any()) } returns 1

		subject.drain() shouldBe 0

		coVerify(exactly = 1) {
			dao.markOutboxTerminal("expired", "START_CONTEXT_EXPIRED", any())
		}
		coVerify(exactly = 1) {
			consumer.deliver(any(), any(), 8, 3, ActivityAutomationStartContext.DURABLE_REPLAY)
		}
	}

	@Test
	fun `fresh callback permit applies only to its exact transition ordinal`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(
			effect(
				stableId = "old-transition",
				ordinal = 1,
				payload = payload(
					kind = ActivityAutomationProjection.KIND_TRANSITION,
					transition = ActivityTransitionType.ENTER.value,
				),
			),
			effect(
				stableId = "current-transition",
				ordinal = 2,
				payload = payload(
					kind = ActivityAutomationProjection.KIND_TRANSITION,
					transition = ActivityTransitionType.ENTER.value,
				),
			),
			effect(stableId = "current-recognition", ordinal = 3),
		)
		val contexts = mutableListOf<ActivityAutomationStartContext>()
		coEvery { consumer.deliver(any(), any(), any(), any(), capture(contexts)) } returns
			ActivityAutomationDeliveryResult.ACCEPTED
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drain(
			limit = 10,
			startPermit = ActivityAutomationStartPermit.FreshTransitionCallback(setOf(2L, 3L)),
			elapsedRealtimeNanos = { 1_200L },
		) shouldBe 3

		contexts shouldBe listOf(
			ActivityAutomationStartContext.DURABLE_REPLAY,
			ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK,
			ActivityAutomationStartContext.DURABLE_REPLAY,
		)
	}

	@Test
	fun `exact callback permit becomes durable replay at the live enqueue deadline`() {
		val evidence = automationEffect().copy(
			transitionType = ActivityTransitionType.ENTER,
		)
		val permit = ActivityAutomationStartPermit.FreshTransitionCallback(setOf(1L))
		val deadline = 7_000_001_100L

		permit.contextFor(evidence) { deadline - 1L } shouldBe
			ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK
		permit.contextFor(evidence) { deadline } shouldBe
			ActivityAutomationStartContext.DURABLE_REPLAY
		permit.contextFor(evidence) { deadline + 1L } shouldBe
			ActivityAutomationStartContext.DURABLE_REPLAY
	}

	@Test
	fun `accepted effect is marked and cannot be delivered twice`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returnsMany
			listOf(listOf(effect()), emptyList())
		coEvery { consumer.deliver(any(), any(), any(), any(), any()) } returns
			ActivityAutomationDeliveryResult.ACCEPTED
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drain() shouldBe 1
		subject.drain() shouldBe 0

		coVerify(exactly = 1) {
			consumer.deliver(any(), any(), 8, 3, ActivityAutomationStartContext.DURABLE_REPLAY)
		}
		coVerify(exactly = 1) { dao.markOutboxDelivered("effect", any()) }
	}

	@Test
	fun `cold recovery completes stale outbox from durable lifecycle acceptance before consumer`() = runTest {
		coEvery { dao.pendingOutbox(any(), any(), any(), any()) } returns listOf(
			effect(
				payload = payload(
					kind = ActivityAutomationProjection.KIND_TRANSITION,
					transition = ActivityTransitionType.ENTER.value,
				),
			),
		)
		coEvery { validator.validate(any()) } returns
			ActivityAutomationEffectValidation.LifecycleIntentAccepted
		coEvery { dao.markOutboxDelivered(any(), any()) } returns 1

		subject.drain() shouldBe 1

		coVerify(exactly = 1) { dao.markOutboxDelivered("effect", any()) }
		coVerify(exactly = 0) { consumer.deliver(any(), any(), any(), any(), any()) }
	}

	@Test
	fun `wrong boot and stale evidence are terminal before automation`() {
		validateActivityAutomationEffectEnvelope(
			automationEffect(),
			currentBootId = "next-boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = authorization(),
			currentAutomationEpoch = 17,
		) shouldBe ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_BOOT")

		validateActivityAutomationEffectEnvelope(
			automationEffect(),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 61_000_001_000,
			authorization = authorization(),
			currentAutomationEpoch = 17,
		) shouldBe ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_EVIDENCE")
	}

	@Test
	fun `authorization identity yields the exact admitted control consent epoch`() {
		validateActivityAutomationEffectEnvelope(
			automationEffect(),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = authorization(),
			currentAutomationEpoch = 17,
		) shouldBe ActivityAutomationEffectValidation.Eligible(8, 17)

		validateActivityAutomationEffectEnvelope(
			automationEffect().copy(authorizationFingerprint = "retired"),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = authorization(),
			currentAutomationEpoch = 17,
		) shouldBe ActivityAutomationEffectValidation.Terminal("OBSOLETE_AUTOMATION_AUTHORIZATION")
	}

	@Test
	fun `automation epoch is independent of policy revision and rotates fail closed`() {
		val policy23 = authorization(sourcePolicyRevision = 23)
		validateActivityAutomationEffectEnvelope(
			effect = automationEffect().copy(automationEpoch = 17),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = policy23,
			currentAutomationEpoch = 17,
		) shouldBe ActivityAutomationEffectValidation.Eligible(8, 17)

		validateActivityAutomationEffectEnvelope(
			effect = automationEffect().copy(automationEpoch = 17),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = policy23,
			currentAutomationEpoch = 18,
		) shouldBe ActivityAutomationEffectValidation.Terminal("STALE_AUTOMATION_EPOCH")

		validateActivityAutomationEffectEnvelope(
			effect = automationEffect().copy(automationEpoch = 18),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000,
			authorization = policy23,
			currentAutomationEpoch = 18,
		) shouldBe ActivityAutomationEffectValidation.Eligible(8, 18)
	}

	@Test
	fun `delayed evidence observed before the current epoch boundary is terminal`() {
		validateActivityAutomationEffectEnvelope(
			effect = automationEffect().copy(observedElapsedRealtimeNanos = 999L),
			currentBootId = "boot",
			currentElapsedRealtimeNanos = 2_000L,
			authorization = authorization(),
			currentAutomationEpoch = 17L,
			currentAutomationBootId = "boot",
			currentAutomationEffectiveElapsedRealtimeNanos = 1_000L,
		) shouldBe ActivityAutomationEffectValidation.Terminal(
			"AUTOMATION_EVIDENCE_PREDATES_EPOCH",
		)
	}

	private fun effect(
		stableId: String = "effect",
		ordinal: Long = 1,
		payload: ByteArray = payload(),
	) =
		SourceProjectionOutboxEntity(
			stableId = stableId,
			projectionId = ActivityAutomationProjection.ID,
			projectionVersion = ActivityAutomationProjection.VERSION,
			admissionOrdinal = ordinal,
			effectKind = ActivityAutomationProjection.OUTBOX_KIND,
			payloadVersion = ActivityAutomationProjection.PAYLOAD_VERSION,
			payload = payload,
			createdAtMs = ordinal,
			deliveredAtMs = null,
		)

	private fun payload(
		kind: Int = ActivityAutomationProjection.KIND_RECOGNITION,
		transition: Int = -1,
	): ByteArray = ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(kind)
			output.writeInt(1)
			output.writeInt(90)
			output.writeInt(transition)
			output.writeUTF("boot")
			output.writeLong(1_000)
			output.writeLong(1_100)
			output.writeLong(1)
			output.writeLong(2)
			output.writeUTF("authorization")
			output.writeLong(0)
			output.writeLong(17)
		}
		bytes.toByteArray()
	}

	private fun automationEffect() = ActivityAutomationDeliveryEnvelope(
		admissionOrdinal = 1,
		activityType = DetectedActivityType.WALKING,
		confidence = 90,
		transitionType = null,
		clockDomainId = "boot",
		observedElapsedRealtimeNanos = 1_000,
		receivedElapsedRealtimeNanos = 1_100,
		registrationGeneration = 1,
		authorizationRevision = 2,
		authorizationFingerprint = "authorization",
		collectedDataEpoch = 0,
		automationEpoch = 17,
	)

	private fun authorization(sourcePolicyRevision: Long = 3): SourceAuthorizationSnapshot {
		val member = SourceAuthorizationEntity(
			sourceKind = SourceKind.ACTIVITY.stableCode,
			registrationGeneration = 1,
			authorizationRevision = 2,
			memberId = "control",
			authorizationFingerprint = "authorization",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			demandId = "demand",
			consumerId = "automatic-start",
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			sourcePolicyRevision = sourcePolicyRevision,
			consentEpoch = 8,
			persistenceEligible = false,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 500,
			effectiveWallTimeMs = 500,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
		)
		return SourceAuthorizationSnapshot(
			authorizationRevision = 2,
			authorizationFingerprint = "authorization",
			purposeEligibilityMask = SourceBrokerPurpose.MASK_CONTROL_AUTOSTART,
			effectiveBootId = "boot",
			effectiveElapsedRealtimeNanos = 500,
			members = listOf(member),
		)
	}
}
