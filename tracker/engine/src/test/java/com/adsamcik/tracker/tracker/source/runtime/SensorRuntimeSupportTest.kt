package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import io.kotest.matchers.shouldBe
import org.junit.Test

class SensorRuntimeSupportTest {
	@Test
	fun `FIFO flush completion proves coverage only for a batching registration`() {
		sensorProviderCoverage(
			batchingEnabled = true,
			flushOutcome = ProviderFlushOutcome.COMPLETE,
		) shouldBe ProviderCoverage.FIFO_COMPLETE_AT_FLUSH_CALL

		sensorProviderCoverage(
			batchingEnabled = true,
			flushOutcome = ProviderFlushOutcome.TIMED_OUT,
		) shouldBe ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE
	}

	@Test
	fun `no-FIFO shutdown covers callbacks that entered before the barrier`() {
		ProviderFlushOutcome.entries.forEach { outcome ->
			sensorProviderCoverage(
				batchingEnabled = false,
				flushOutcome = outcome,
			) shouldBe ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER
		}
	}

	@Test
	fun `active checkpoint becomes a durable classified process gap on re-registration`() {
		val original = SensorRuntimeCheckpoint(
			lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
			metrics = RuntimeAdmissionSnapshot(7L, 42L, 0L, null, null, emptySet()),
			componentStateVersion = 1,
			componentPayload = byteArrayOf(1, 2, 3),
		)
		val entity = entity(encodeSensorRuntimeCheckpoint(original))

		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(entity, 1))
			.withProcessRestartIfNeeded(
				priorRegistrationGeneration = 1L,
				currentRegistrationGeneration = 2L,
				lastProviderSequence = 7L,
			)
		val roundTrip = requireNotNull(
			decodeSensorRuntimeCheckpoint(
				entity(encodeSensorRuntimeCheckpoint(decoded)).copy(registrationGeneration = 2L),
				1,
			),
		)

		roundTrip.metrics.failedAdmissionCount shouldBe 1L
		roundTrip.metrics.unresolvedSequenceStart shouldBe 8L
		roundTrip.metrics.unresolvedSequenceEndInclusive shouldBe 8L
		roundTrip.metrics.gapClassifications shouldBe setOf(RuntimeGapClassification.PROCESS_RESTARTED)
		roundTrip.componentPayload.toList() shouldBe listOf<Byte>(1, 2, 3)
	}

	@Test
	fun `clean quiesce does not invent a process gap on the next generation`() {
		val checkpoint = SensorRuntimeCheckpoint(
			RuntimeCheckpointLifecycle.QUIESCED,
			RuntimeAdmissionSnapshot(7L, 42L, 0L, null, null, emptySet()),
			1,
			byteArrayOf(),
		).withProcessRestartIfNeeded(1L, 2L, 7L)

		checkpoint.metrics.failedAdmissionCount shouldBe 0L
		checkpoint.metrics.gapClassifications shouldBe emptySet()
	}

	@Test
	fun `missing checkpoint after a prior registration is an explicit process gap`() {
		missingSensorCheckpointAfterRegistration(0L, 1) shouldBe null

		val recovered = requireNotNull(missingSensorCheckpointAfterRegistration(1L, 1))
		recovered.metrics.unresolvedSequenceStart shouldBe 1L
		recovered.metrics.unresolvedSequenceEndInclusive shouldBe 1L
		recovered.metrics.gapClassifications shouldBe setOf(RuntimeGapClassification.PROCESS_RESTARTED)
	}

	private fun entity(payload: ByteArray) = SourceRuntimeStateEntity(
		sourceKind = 3,
		ownerScope = "session",
		sourceInstanceId = "instance",
		clockDomainId = "boot",
		registrationGeneration = 1L,
		lastProviderSequence = 7L,
		lastAdmittedSourceSequence = 7L,
		lastAdmissionOrdinal = 42L,
		stateVersion = 2,
		payload = payload,
		updatedAtMs = 1_000L,
	)
}
