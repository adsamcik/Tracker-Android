package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.dao.SourceRunAdmissionHighWaterRow
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.runtime.SessionCutoff
import com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ActivityRetirementRecoveryTest {
	@Test
	fun `known lost callback retires normally with honest partial coverage even without a saved event`() {
		val lost = registration.copy(failureCode = "DEMAND_REMOVED|" +
			ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX + 3L)
		val empty = SourceRunAdmissionHighWaterRow(0L, 0L)
		assertTrue(hasOrderlyActivityRetirement(lost, barrier.copy(lastSourceSequence = 0L,
			lastAdmissionOrdinal = 0L), empty))
		val ack = orderlyActivityRetirementAcknowledgement(
			SourceProviderKey(SourceInstanceId("activity"), 79L), empty,
			SessionCutoff("session", 2_000L, 2_000L, 3_000L), "run", failedCaptureDelivery = true,
		)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertTrue(ack.appDrainComplete)
		assertEquals(1L, ack.failedAdmissionCount)
		assertNull(ack.lastAdmissionOrdinal)
		assertNull(ack.lastDurablyAdmittedSequence)
		assertNull(ack.unresolvedSequenceStart)
		assertNull(ack.unresolvedSequenceEndInclusive)
		assertFalse(hasOrderlyActivityRetirement(lost.copy(failureCode = "DEMAND_REMOVED|PROVIDER_FAILED"), barrier, empty))
	}

	@Test
	fun `an earlier capture loss does not invent a restart gap in a later clean join`() {
		val later = registration.copy(captureCallbackBarrierAuthorizationRevision = 4L,
			failureCode = "DEMAND_REMOVED|" + ProviderRegistrationGenerationEntity.FAILURE_ACTIVITY_CALLBACK_LOSS_PREFIX + 3L)
		assertTrue(hasOrderlyActivityRetirement(later, barrier.copy(throughAuthorizationRevision = 4L), highWater))
		val ack = orderlyActivityRetirementAcknowledgement(SourceProviderKey(SourceInstanceId("activity"), 79L),
			highWater, SessionCutoff("session", 2_000L, 2_000L, 3_000L), "run", failedCaptureDelivery = false)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
		assertEquals(0L, ack.failedAdmissionCount)
		assertNull(ack.unresolvedSequenceStart)
	}

	@Test
	fun `clean Activity handoff retains its drained prefix instead of inventing a restart gap`() {
		assertTrue(hasOrderlyActivityRetirement(registration, barrier, highWater))
		assertTrue(hasOrderlyActivityRetirement(registration, barrier.copy(lastSourceSequence = 0L,
			lastAdmissionOrdinal = 0L), SourceRunAdmissionHighWaterRow(0L, 0L)))
	}

	@Test
	fun `a saved first Activity callback retains its real zero based source sequence`() {
		val ack = orderlyActivityRetirementAcknowledgement(
			SourceProviderKey(SourceInstanceId("activity"), 79L), SourceRunAdmissionHighWaterRow(100L, 0L),
			SessionCutoff("session", 2_000L, 2_000L, 3_000L), "run", failedCaptureDelivery = false,
		)
		assertEquals(0L, ack.lastDurablyAdmittedSequence)
		assertEquals(100L, ack.lastAdmissionOrdinal)
		assertEquals(SourceStopStatus.COMPLETE, ack.status)
	}

	@Test
	fun `process loss and incomplete removal retain unavailable coverage`() {
		assertFalse(hasOrderlyActivityRetirement(registration.copy(
			failureCode = ProviderRegistrationGenerationEntity.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED), barrier, highWater))
		assertFalse(hasOrderlyActivityRetirement(registration.copy(
			status = ProviderRegistrationGenerationEntity.STATUS_RETIRING), barrier, highWater))
	}

	@Test
	fun `missing or incomplete callback drain cannot repair a retirement`() {
		assertFalse(hasOrderlyActivityRetirement(registration, null, highWater))
		assertFalse(hasOrderlyActivityRetirement(registration, barrier.copy(throughAuthorizationRevision = 2L), highWater))
		assertFalse(hasOrderlyActivityRetirement(registration, barrier.copy(lastSourceSequence = 30L), highWater))
		assertFalse(hasOrderlyActivityRetirement(registration, barrier.copy(lastAdmissionOrdinal = 99L), highWater))
	}

	@Test
	fun `callback drain must belong to that Activity provider`() {
		assertFalse(hasOrderlyActivityRetirement(registration, barrier.copy(sourceInstanceId = "other"), highWater))
		assertFalse(hasOrderlyActivityRetirement(registration, barrier.copy(registrationGeneration = 80L), highWater))
		assertFalse(hasOrderlyActivityRetirement(registration.copy(sourceKind = SourceKind.PRESSURE.stableCode),
			barrier.copy(sourceKind = SourceKind.PRESSURE.stableCode), highWater))
	}

	private val highWater = SourceRunAdmissionHighWaterRow(lastAdmissionOrdinal = 100L, lastSourceSequence = 31L)
	private val barrier = SourceCaptureAdmissionBarrierEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode, registrationGeneration = 79L, sourceInstanceId = "activity",
		throughAuthorizationRevision = 3L, lastAdmissionOrdinal = 100L, lastSourceSequence = 31L,
		sealedElapsedRealtimeNanos = 2_000L, sealedAtMs = 2_000L,
	)
	private val registration = ProviderRegistrationGenerationEntity(
		sourceKind = SourceKind.ACTIVITY.stableCode, registrationGeneration = 79L, sourceInstanceId = "activity",
		ownerScope = "activity-arbiter", clockDomainId = "boot", physicalConfigurationFingerprint = "continuous",
		collectedDataEpoch = 0L, providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
		providerProcessIncarnationId = "same-process", status = ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		reservedAtMs = 1_000L, reservedElapsedRealtimeNanos = 1_000L,
		acceptedAtMs = 1_000L, acceptedElapsedRealtimeNanos = 1_000L,
		retiredAtMs = 2_000L, retiredElapsedRealtimeNanos = 2_000L, failureCode = "DEMAND_REMOVED",
		captureCallbackBarrierAuthorizationRevision = 3L,
	)
}
