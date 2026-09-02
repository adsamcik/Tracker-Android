package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PressureRuntimeRecoveryTest {
	@Test
	fun `restart reserves after unresolved high-water rather than stale row sequence`() {
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = 60L,
				lastAdmissionOrdinal = 70L,
				failedAdmissionCount = 2L,
				unresolvedSequenceStart = 100L,
				unresolvedSequenceEndInclusive = 101L,
				gapClassifications = setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW),
			),
			componentStateVersion = PRESSURE_RUNTIME_COMPONENT_VERSION,
			componentPayload = ByteArray(0),
		)

		val recovery = recoverPressureRuntimeState(
			saved = runtimeState(7L, lastProviderSequence = 60L, checkpoint = checkpoint),
			currentRegistrationGeneration = 7L,
			reusedActiveRegistration = true,
		)

		assertEquals(102L, recovery.callbackEntrySequence)
		assertEquals(102L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(103L, checkedNextPressureProviderSequence(recovery.callbackEntrySequence))
		assertFailsWith<ArithmeticException> { checkedNextPressureProviderSequence(Long.MAX_VALUE) }
	}

	@Test
	fun `process restart gaps future partial after atomically durable multi-sequence pressure window`() {
		val absoluteGaps = RuntimeAdmissionSnapshot(
			lastDurablyAdmittedSequence = null,
			lastAdmissionOrdinal = null,
			failedAdmissionCount = 1L,
			unresolvedSequenceStart = 2L,
			unresolvedSequenceEndInclusive = 2L,
			gapClassifications = setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW),
		)
		val checkpoint = SensorAdmissionCheckpoint(
			source = com.adsamcik.tracker.tracker.source.model.SourceKind.PRESSURE,
			ownerScope = "source-broker:4",
			sourceInstanceId = "pressure-atomic",
			clockDomainId = "boot-1",
			registrationGeneration = 7L,
			providerSequenceThrough = 8L, // One durable window covered provider sequences 3..8.
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			failedAdmissionCount = absoluteGaps.failedAdmissionCount,
			unresolvedSequenceStart = absoluteGaps.unresolvedSequenceStart,
			unresolvedSequenceEndInclusive = absoluteGaps.unresolvedSequenceEndInclusive,
			gapClassifications = absoluteGaps.gapClassifications,
			componentStateVersion = PRESSURE_RUNTIME_COMPONENT_VERSION,
			componentPayload = pressureAtomicRuntimeCheckpoint(
				absoluteGaps,
				causalOrderElapsedRealtimeNanos = 987_654_321L,
			).componentPayload,
			updatedAtMs = pressureCheckpointOrderMillis(987_654_321L),
		)
		val durableState = checkpoint.toRuntimeState(
			admissionOrdinal = 91L,
			causalOrderElapsedRealtimeNanos = 987_654_321L,
		)
		assertTrue(checkpoint.componentPayload.isEmpty())

		val recovery = recoverPressureRuntimeState(
			saved = durableState,
			currentRegistrationGeneration = 7L,
			reusedActiveRegistration = true,
		)

		assertEquals(8L, recovery.metrics?.lastDurablyAdmittedSequence)
		assertEquals(91L, recovery.metrics?.lastAdmissionOrdinal)
		assertEquals(2L, recovery.metrics?.failedAdmissionCount)
		assertEquals(2L, recovery.metrics?.unresolvedSequenceStart)
		assertEquals(9L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(
			setOf(
				RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW,
				RuntimeGapClassification.PROCESS_RESTARTED,
			),
			recovery.metrics?.gapClassifications,
		)
		assertEquals(9L, recovery.callbackEntrySequence)
		assertTrue(recovery.requiresPreAcquisitionCheckpoint)
	}

	@Test
	fun `active runtime restores durable metrics but never stitches its partial window`() {
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(20L, 91L, 0L, null, null, emptySet()),
			componentStateVersion = 2,
			componentPayload = byteArrayOf(1, 2, 3, 4), // A legacy serialized partial accumulator.
		)
		val recovery = recoverPressureRuntimeState(
			saved = runtimeState(7L, 20L, checkpoint),
			currentRegistrationGeneration = 7L,
			reusedActiveRegistration = true,
		)

		assertEquals(20L, recovery.metrics?.lastDurablyAdmittedSequence)
		assertEquals(91L, recovery.metrics?.lastAdmissionOrdinal)
		assertEquals(21L, recovery.metrics?.unresolvedSequenceStart)
		assertEquals(21L, recovery.metrics?.unresolvedSequenceEndInclusive)
		assertEquals(
			setOf(RuntimeGapClassification.PROCESS_RESTARTED),
			recovery.metrics?.gapClassifications,
		)
		assertEquals(21L, recovery.callbackEntrySequence)
		assertTrue(recovery.requiresPreAcquisitionCheckpoint)

		val fresh = PressureWindowAccumulator(
			windowNanos = 1_000L,
			effectiveSamplePeriodMicros = 1,
			effectiveMaximumReportLatencyMicros = 0,
			boundary = PressureAccumulatorBoundary(7L, "authorization-a", 3L),
		)
		assertNull(fresh.add(1_010f, 5_000L, 22L))
		val terminal = assertNotNull(fresh.drain())
		assertEquals(1, terminal.sampleCount)
		assertEquals(22L, terminal.firstProviderSequence)
	}

	@Test
	fun `reused active registration with no checkpoint records process restart`() {
		val recovery = recoverPressureRuntimeState(
			saved = null,
			currentRegistrationGeneration = 4L,
			reusedActiveRegistration = true,
		)

		assertEquals(1L, recovery.callbackEntrySequence)
		assertEquals(1L, recovery.metrics?.failedAdmissionCount)
		assertEquals(
			setOf(RuntimeGapClassification.PROCESS_RESTARTED),
			recovery.metrics?.gapClassifications,
		)
		assertTrue(recovery.requiresPreAcquisitionCheckpoint)
	}

	@Test
	fun `clean terminal checkpoint does not classify a restart`() {
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.QUIESCED,
			metrics = RuntimeAdmissionSnapshot(9L, 44L, 0L, null, null, emptySet()),
			componentStateVersion = PRESSURE_RUNTIME_COMPONENT_VERSION,
			componentPayload = ByteArray(0),
		)
		val recovery = recoverPressureRuntimeState(
			saved = runtimeState(5L, 9L, checkpoint),
			currentRegistrationGeneration = 5L,
			reusedActiveRegistration = false,
		)

		assertEquals(9L, recovery.callbackEntrySequence)
		assertEquals(0L, recovery.metrics?.failedAdmissionCount)
		assertEquals(emptySet(), recovery.metrics?.gapClassifications)
		assertFalse(recovery.requiresPreAcquisitionCheckpoint)
	}

	private fun runtimeState(
		generation: Long,
		lastProviderSequence: Long,
		checkpoint: SensorRuntimeCheckpoint,
	) = SourceRuntimeStateEntity(
		sourceKind = 4,
		ownerScope = "source-broker:pressure",
		sourceInstanceId = "pressure-1",
		clockDomainId = "boot-1",
		registrationGeneration = generation,
		lastProviderSequence = lastProviderSequence,
		lastAdmittedSourceSequence = checkpoint.metrics.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = checkpoint.metrics.lastAdmissionOrdinal,
		stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
		payload = encodeSensorRuntimeCheckpoint(checkpoint),
		updatedAtMs = 10L,
	)
}
