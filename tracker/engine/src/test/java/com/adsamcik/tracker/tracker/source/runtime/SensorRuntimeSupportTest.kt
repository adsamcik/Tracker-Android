package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SensorRuntimeSupportTest {
	@Test
	fun `legacy sink reports atomic checkpoint admission as explicitly unsupported`() = runTest {
		val sink = SourceEventSink { SourceAdmissionHandoff.Durable(1L) }

		val result = sink.admit(mockk<SourceEvidenceCandidate<*>>(), atomicCheckpoint())

		result shouldBe SourceAdmissionHandoff.RetryableFailure(
			SourceAdmissionFailureCode.ATOMIC_CHECKPOINT_UNSUPPORTED,
		)
	}

	@Test
	fun `atomic checkpoint binds durable high water to Room admission ordinal`() {
		val state = atomicCheckpoint().toRuntimeState(
			admissionOrdinal = 91L,
			causalOrderElapsedRealtimeNanos = 12_345L,
		)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(state, 1))

		state.lastProviderSequence shouldBe 7L
		state.lastAdmittedSourceSequence shouldBe 7L
		state.lastAdmissionOrdinal shouldBe 91L
		decoded.metrics.lastDurablyAdmittedSequence shouldBe 7L
		decoded.metrics.lastAdmissionOrdinal shouldBe 91L
		decoded.metrics.failedAdmissionCount shouldBe 2L
		decoded.metrics.unresolvedSequenceStart shouldBe 3L
		decoded.metrics.unresolvedSequenceEndInclusive shouldBe 4L
		decoded.componentPayload.toList() shouldBe listOf<Byte>(1, 2, 3)
		decoded.causalOrderElapsedRealtimeNanos shouldBe 12_345L
	}

	@Test
	fun `later terminal state wins despite wall clock rollback`() {
		val active = runtimeState(
			checkpoint(RuntimeCheckpointLifecycle.ACTIVE, causalOrder = 100L, componentByte = 1),
			providerSequence = 7L,
			updatedAtMs = 5_000L,
		)
		val terminal = runtimeState(
			checkpoint(RuntimeCheckpointLifecycle.QUIESCED, causalOrder = 200L, componentByte = 2),
			providerSequence = 7L,
			updatedAtMs = 100L,
		)

		val merged = mergeSensorRuntimeStates(active, terminal, 1)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(merged, 1))

		decoded.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
		decoded.componentPayload.toList() shouldBe listOf<Byte>(2)
		decoded.causalOrderElapsedRealtimeNanos shouldBe 200L
	}

	@Test
	fun `delayed active callback with higher provider water cannot replace a later timeout`() {
		val terminal = runtimeState(
			checkpoint(RuntimeCheckpointLifecycle.TIMED_OUT, causalOrder = 300L, componentByte = 9),
			providerSequence = 7L,
			updatedAtMs = 100L,
		)
		val delayedActive = runtimeState(
			checkpoint(
				RuntimeCheckpointLifecycle.ACTIVE,
				causalOrder = 200L,
				componentByte = 3,
				metrics = RuntimeAdmissionSnapshot(8L, 93L, 0L, null, null, emptySet()),
			),
			providerSequence = 8L,
			updatedAtMs = 10_000L,
		)

		val merged = mergeSensorRuntimeStates(terminal, delayedActive, 1)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(merged, 1))

		merged.lastProviderSequence shouldBe 8L
		decoded.lifecycle shouldBe RuntimeCheckpointLifecycle.TIMED_OUT
		decoded.componentPayload.toList() shouldBe listOf<Byte>(9)
		decoded.metrics.lastDurablyAdmittedSequence shouldBe 8L
		decoded.metrics.lastAdmissionOrdinal shouldBe 93L
	}

	@Test
	fun `terminal lifecycle wins equal causal order independent of merge direction`() {
		val active = runtimeState(
			checkpoint(RuntimeCheckpointLifecycle.ACTIVE, causalOrder = 400L, componentByte = 1),
			providerSequence = 7L,
			updatedAtMs = 100L,
		)
		val terminal = runtimeState(
			checkpoint(RuntimeCheckpointLifecycle.QUIESCED, causalOrder = 400L, componentByte = 2),
			providerSequence = 7L,
			updatedAtMs = 100L,
		)

		val forward = requireNotNull(decodeSensorRuntimeCheckpoint(
			mergeSensorRuntimeStates(active, terminal, 1),
			1,
		))
		val reverse = requireNotNull(decodeSensorRuntimeCheckpoint(
			mergeSensorRuntimeStates(terminal, active, 1),
			1,
		))

		forward.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
		reverse.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
		forward.componentPayload.toList() shouldBe listOf<Byte>(2)
		reverse.componentPayload.toList() shouldBe listOf<Byte>(2)
	}

	@Test
	fun `older duplicate heals terminal admission metrics with row payload parity`() {
		val terminal = runtimeState(
			checkpoint(
				RuntimeCheckpointLifecycle.QUIESCED,
				causalOrder = 500L,
				componentByte = 9,
				metrics = RuntimeAdmissionSnapshot(
					null,
					null,
					1L,
					8L,
					8L,
					setOf(RuntimeGapClassification.DRAIN_TIMED_OUT),
				),
			),
			providerSequence = 7L,
			updatedAtMs = 500L,
		)
		val duplicate = runtimeState(
			checkpoint(
				RuntimeCheckpointLifecycle.ACTIVE,
				causalOrder = 400L,
				componentByte = 1,
				metrics = RuntimeAdmissionSnapshot(7L, 91L, 0L, null, null, emptySet()),
			),
			providerSequence = 7L,
			updatedAtMs = 10_000L,
		)

		val merged = mergeSensorRuntimeStates(terminal, duplicate, 1)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(merged, 1))

		decoded.lifecycle shouldBe RuntimeCheckpointLifecycle.QUIESCED
		decoded.componentPayload.toList() shouldBe listOf<Byte>(9)
		merged.lastAdmittedSourceSequence shouldBe 7L
		merged.lastAdmissionOrdinal shouldBe 91L
		decoded.metrics.lastDurablyAdmittedSequence shouldBe merged.lastAdmittedSourceSequence
		decoded.metrics.lastAdmissionOrdinal shouldBe merged.lastAdmissionOrdinal
		decoded.metrics.unresolvedSequenceStart shouldBe 8L
		decoded.metrics.gapClassifications shouldBe setOf(RuntimeGapClassification.DRAIN_TIMED_OUT)
	}

	@Test
	fun `reordered admission joins durable sequence and ordinal as one pair`() {
		val sequenceTen = runtimeState(
			checkpoint(
				RuntimeCheckpointLifecycle.ACTIVE,
				causalOrder = 100L,
				componentByte = 1,
				metrics = RuntimeAdmissionSnapshot(10L, 100L, 2L, null, null, emptySet()),
			),
			providerSequence = 10L,
			updatedAtMs = 100L,
		)
		val reorderedSequenceNine = runtimeState(
			checkpoint(
				RuntimeCheckpointLifecycle.ACTIVE,
				causalOrder = 90L,
				componentByte = 2,
				metrics = RuntimeAdmissionSnapshot(9L, 200L, 1L, null, null, emptySet()),
			),
			providerSequence = 9L,
			updatedAtMs = 200L,
		)

		val merged = mergeSensorRuntimeStates(sequenceTen, reorderedSequenceNine, 1)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(merged, 1))

		decoded.metrics.lastDurablyAdmittedSequence shouldBe 10L
		decoded.metrics.lastAdmissionOrdinal shouldBe 100L
		decoded.metrics.failedAdmissionCount shouldBe 2L
		merged.lastAdmittedSourceSequence shouldBe 10L
		merged.lastAdmissionOrdinal shouldBe 100L
	}

	@Test
	fun `v2 checkpoint remains readable and upgrades with zero legacy causal order`() {
		val legacy = checkpoint(
			RuntimeCheckpointLifecycle.QUIESCED,
			causalOrder = 999L,
			componentByte = 4,
		)
		val v2 = runtimeState(legacy, providerSequence = 7L, updatedAtMs = 999L).copy(
			stateVersion = 2,
			payload = encodeV2SensorRuntimeCheckpoint(legacy),
		)
		val decoded = requireNotNull(decodeSensorRuntimeCheckpoint(v2, 1))
		decoded.causalOrderElapsedRealtimeNanos shouldBe 0L

		val upgraded = mergeSensorRuntimeStates(
			v2,
			runtimeState(
				checkpoint(RuntimeCheckpointLifecycle.ACTIVE, causalOrder = 1L, componentByte = 5),
				providerSequence = 8L,
				updatedAtMs = 1L,
			),
			1,
		)
		requireNotNull(decodeSensorRuntimeCheckpoint(upgraded, 1))
			.causalOrderElapsedRealtimeNanos shouldBe 1L
		upgraded.stateVersion shouldBe 3
	}

	@Test
	fun `standalone checkpoint preserves caller supplied barrier causal order`() {
		val supplied = checkpoint(
			RuntimeCheckpointLifecycle.QUIESCED,
			causalOrder = 777L,
			componentByte = 1,
		)

		supplied.withFallbackCausalOrder(999L).causalOrderElapsedRealtimeNanos shouldBe 777L
		checkpoint(RuntimeCheckpointLifecycle.ACTIVE, 0L, 1)
			.withFallbackCausalOrder(999L)
			.causalOrderElapsedRealtimeNanos shouldBe 999L
	}

	@Test
	fun `delayed durable replay cannot regress in-memory high water`() {
		val metrics = RuntimeAdmissionMetrics(lastDurablyAdmittedSequence = 8L, lastAdmissionOrdinal = 92L)

		metrics.recordDurable(sequence = 7L, ordinal = 91L)

		metrics.snapshot().lastDurablyAdmittedSequence shouldBe 8L
		metrics.snapshot().lastAdmissionOrdinal shouldBe 92L
	}

	@Test
	fun `FIFO flush completion does not prove provider completeness`() {
		sensorProviderCoverage(
			batchingEnabled = true,
			flushOutcome = ProviderFlushOutcome.COMPLETE,
		) shouldBe ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE

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

	@Test
	fun `callback overflow is durably summarized without unbounded per-event state`() {
		val metrics = RuntimeAdmissionMetrics()
		metrics.recordFailure(10L, 1_010L, RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW)
		val checkpoint = SensorRuntimeCheckpoint(
			RuntimeCheckpointLifecycle.ACTIVE,
			metrics.snapshot(),
			componentStateVersion = 1,
			componentPayload = ByteArray(0),
		)
		val restored = requireNotNull(
			decodeSensorRuntimeCheckpoint(
				runtimeState(checkpoint, providerSequence = 1_010L, updatedAtMs = 1_000L),
				1,
			),
		)

		restored.metrics.failedAdmissionCount shouldBe 1_001L
		restored.metrics.unresolvedSequenceStart shouldBe 10L
		restored.metrics.unresolvedSequenceEndInclusive shouldBe 1_010L
		restored.metrics.gapClassifications shouldBe
			setOf(RuntimeGapClassification.CALLBACK_BUFFER_OVERFLOW)
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
		stateVersion = 3,
		payload = payload,
		updatedAtMs = 1_000L,
	)

	private fun checkpoint(
		lifecycle: RuntimeCheckpointLifecycle,
		causalOrder: Long,
		componentByte: Byte,
		metrics: RuntimeAdmissionSnapshot = RuntimeAdmissionSnapshot(
			7L,
			42L,
			0L,
			null,
			null,
			emptySet(),
		),
	) = SensorRuntimeCheckpoint(
		lifecycle = lifecycle,
		metrics = metrics,
		componentStateVersion = 1,
		componentPayload = byteArrayOf(componentByte),
		causalOrderElapsedRealtimeNanos = causalOrder,
	)

	private fun runtimeState(
		checkpoint: SensorRuntimeCheckpoint,
		providerSequence: Long,
		updatedAtMs: Long,
	) = SourceRuntimeStateEntity(
		sourceKind = SourceKind.STEPS.stableCode,
		ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
		sourceInstanceId = "step-instance",
		clockDomainId = "boot",
		registrationGeneration = 1L,
		lastProviderSequence = providerSequence,
		lastAdmittedSourceSequence = checkpoint.metrics.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = checkpoint.metrics.lastAdmissionOrdinal,
		stateVersion = 3,
		payload = encodeSensorRuntimeCheckpoint(checkpoint),
		updatedAtMs = updatedAtMs,
	)

	private fun encodeV2SensorRuntimeCheckpoint(checkpoint: SensorRuntimeCheckpoint): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeInt(0x53524350)
				output.writeInt(checkpoint.lifecycle.ordinal)
				output.writeLong(checkpoint.metrics.lastDurablyAdmittedSequence ?: Long.MIN_VALUE)
				output.writeLong(checkpoint.metrics.lastAdmissionOrdinal ?: Long.MIN_VALUE)
				output.writeLong(checkpoint.metrics.failedAdmissionCount)
				output.writeLong(checkpoint.metrics.unresolvedSequenceStart ?: Long.MIN_VALUE)
				output.writeLong(checkpoint.metrics.unresolvedSequenceEndInclusive ?: Long.MIN_VALUE)
				val classifications = checkpoint.metrics.gapClassifications.sortedBy { it.ordinal }
				output.writeInt(classifications.size)
				classifications.forEach { output.writeInt(it.ordinal) }
				output.writeInt(checkpoint.componentStateVersion)
				output.writeInt(checkpoint.componentPayload.size)
				output.write(checkpoint.componentPayload)
			}
			bytes.toByteArray()
		}

	private fun atomicCheckpoint() = SensorAdmissionCheckpoint(
		source = SourceKind.STEPS,
		ownerScope = "source-broker:${SourceKind.STEPS.stableCode}",
		sourceInstanceId = "step-instance",
		clockDomainId = "boot",
		registrationGeneration = 1L,
		providerSequenceThrough = 7L,
		lifecycle = RuntimeCheckpointLifecycle.ACTIVE,
		failedAdmissionCount = 2L,
		unresolvedSequenceStart = 3L,
		unresolvedSequenceEndInclusive = 4L,
		gapClassifications = setOf(RuntimeGapClassification.ADMISSION_FAILED),
		componentStateVersion = 1,
		componentPayload = byteArrayOf(1, 2, 3),
		updatedAtMs = 1_000L,
	)
}
