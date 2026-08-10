package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal enum class RuntimeGapClassification {
	CALLBACK_BUFFER_OVERFLOW,
	SEQUENCE_ALLOCATION_FAILED,
	ADMISSION_FAILED,
	PROCESS_RESTARTED,
	DRAIN_TIMED_OUT,
}

internal enum class RuntimeCheckpointLifecycle { ACTIVE, QUIESCED, TIMED_OUT }

internal data class RuntimeAdmissionSnapshot(
	val lastDurablyAdmittedSequence: Long?,
	val lastAdmissionOrdinal: Long?,
	val failedAdmissionCount: Long,
	val unresolvedSequenceStart: Long?,
	val unresolvedSequenceEndInclusive: Long?,
	val gapClassifications: Set<RuntimeGapClassification>,
)

internal class RuntimeAdmissionMetrics(
	lastDurablyAdmittedSequence: Long? = null,
	lastAdmissionOrdinal: Long? = null,
	failedAdmissionCount: Long = 0L,
	unresolvedSequenceStart: Long? = null,
	unresolvedSequenceEndInclusive: Long? = null,
	gapClassifications: Set<RuntimeGapClassification> = emptySet(),
) {
	private val lock = Any()
	private var lastDurablyAdmittedSequence = lastDurablyAdmittedSequence
	private var lastAdmissionOrdinal = lastAdmissionOrdinal
	private var failedAdmissionCount = failedAdmissionCount
	private var unresolvedSequenceStart: Long? = unresolvedSequenceStart
	private var unresolvedSequenceEndInclusive: Long? = unresolvedSequenceEndInclusive
	private val gapClassifications = gapClassifications.toMutableSet()

	fun recordFailure(
		firstSequence: Long,
		lastSequence: Long = firstSequence,
		classification: RuntimeGapClassification = RuntimeGapClassification.ADMISSION_FAILED,
	) {
		require(firstSequence <= lastSequence)
		synchronized(lock) {
			failedAdmissionCount += lastSequence - firstSequence + 1L
			unresolvedSequenceStart = unresolvedSequenceStart?.let { minOf(it, firstSequence) } ?: firstSequence
			unresolvedSequenceEndInclusive = unresolvedSequenceEndInclusive?.let { maxOf(it, lastSequence) } ?: lastSequence
			gapClassifications += classification
		}
	}

	fun recordDurable(sequence: Long, ordinal: Long) {
		synchronized(lock) {
			lastDurablyAdmittedSequence = sequence
			lastAdmissionOrdinal = ordinal
		}
	}

	fun snapshot(): RuntimeAdmissionSnapshot = synchronized(lock) {
		RuntimeAdmissionSnapshot(
			lastDurablyAdmittedSequence,
			lastAdmissionOrdinal,
			failedAdmissionCount,
			unresolvedSequenceStart,
			unresolvedSequenceEndInclusive,
			gapClassifications.toSet(),
		)
	}
}

internal data class SensorRuntimeCheckpoint(
	val lifecycle: RuntimeCheckpointLifecycle,
	val metrics: RuntimeAdmissionSnapshot,
	val componentStateVersion: Int,
	val componentPayload: ByteArray,
)

internal fun decodeSensorRuntimeCheckpoint(
	state: SourceRuntimeStateEntity?,
	legacyComponentStateVersion: Int,
): SensorRuntimeCheckpoint? {
	state ?: return null
	if (state.stateVersion != SENSOR_RUNTIME_CHECKPOINT_VERSION) {
		return SensorRuntimeCheckpoint(
			RuntimeCheckpointLifecycle.ACTIVE,
			RuntimeAdmissionSnapshot(
				state.lastAdmittedSourceSequence,
				state.lastAdmissionOrdinal,
				0L,
				null,
				null,
				emptySet(),
			),
			legacyComponentStateVersion,
			state.payload,
		)
	}
	return runCatching {
		DataInputStream(ByteArrayInputStream(state.payload)).use { input ->
			require(input.readInt() == SENSOR_RUNTIME_CHECKPOINT_MAGIC)
			val lifecycle = RuntimeCheckpointLifecycle.entries[input.readInt()]
			val lastDurable = input.readLong().takeUnless { it == NULL_LONG }
			val lastOrdinal = input.readLong().takeUnless { it == NULL_LONG }
			val failures = input.readLong()
			val unresolvedStart = input.readLong().takeUnless { it == NULL_LONG }
			val unresolvedEnd = input.readLong().takeUnless { it == NULL_LONG }
			val classifications = buildSet {
				repeat(input.readInt()) { add(RuntimeGapClassification.entries[input.readInt()]) }
			}
			val componentVersion = input.readInt()
			val componentPayload = ByteArray(input.readInt()).also(input::readFully)
			require(input.available() == 0)
			SensorRuntimeCheckpoint(
				lifecycle,
				RuntimeAdmissionSnapshot(
					lastDurable,
					lastOrdinal,
					failures,
					unresolvedStart,
					unresolvedEnd,
					classifications,
				),
				componentVersion,
				componentPayload,
			)
		}
	}.getOrNull()
}

internal fun SensorRuntimeCheckpoint.withProcessRestartIfNeeded(
	priorRegistrationGeneration: Long,
	currentRegistrationGeneration: Long,
	lastProviderSequence: Long,
): SensorRuntimeCheckpoint {
	if (lifecycle != RuntimeCheckpointLifecycle.ACTIVE ||
		priorRegistrationGeneration >= currentRegistrationGeneration
	) return this
	val gapSequence = lastProviderSequence + 1L
	val restored = RuntimeAdmissionMetrics(
		metrics.lastDurablyAdmittedSequence,
		metrics.lastAdmissionOrdinal,
		metrics.failedAdmissionCount,
		metrics.unresolvedSequenceStart,
		metrics.unresolvedSequenceEndInclusive,
		metrics.gapClassifications,
	).also {
		it.recordFailure(gapSequence, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	}
	return copy(lifecycle = RuntimeCheckpointLifecycle.ACTIVE, metrics = restored.snapshot())
}

internal fun missingSensorCheckpointAfterRegistration(
	registrationGeneration: Long,
	componentStateVersion: Int,
): SensorRuntimeCheckpoint? {
	if (registrationGeneration <= 0L) return null
	val metrics = RuntimeAdmissionMetrics().also {
		it.recordFailure(1L, classification = RuntimeGapClassification.PROCESS_RESTARTED)
	}
	return SensorRuntimeCheckpoint(
		RuntimeCheckpointLifecycle.ACTIVE,
		metrics.snapshot(),
		componentStateVersion,
		ByteArray(0),
	)
}

internal fun encodeSensorRuntimeCheckpoint(checkpoint: SensorRuntimeCheckpoint): ByteArray =
	ByteArrayOutputStream().use { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(SENSOR_RUNTIME_CHECKPOINT_MAGIC)
			output.writeInt(checkpoint.lifecycle.ordinal)
			output.writeLong(checkpoint.metrics.lastDurablyAdmittedSequence ?: NULL_LONG)
			output.writeLong(checkpoint.metrics.lastAdmissionOrdinal ?: NULL_LONG)
			output.writeLong(checkpoint.metrics.failedAdmissionCount)
			output.writeLong(checkpoint.metrics.unresolvedSequenceStart ?: NULL_LONG)
			output.writeLong(checkpoint.metrics.unresolvedSequenceEndInclusive ?: NULL_LONG)
			val classifications = checkpoint.metrics.gapClassifications.sortedBy { it.ordinal }
			output.writeInt(classifications.size)
			classifications.forEach { output.writeInt(it.ordinal) }
			output.writeInt(checkpoint.componentStateVersion)
			output.writeInt(checkpoint.componentPayload.size)
			output.write(checkpoint.componentPayload)
		}
		bytes.toByteArray()
	}

internal suspend fun SourceRegistrationRepository.saveSensorRuntimeCheckpoint(
	registration: SourceRegistration,
	lastProviderSequence: Long,
	checkpoint: SensorRuntimeCheckpoint,
	updatedAtMs: Long,
) = saveRuntimeState(
	registration = registration,
	lastProviderSequence = lastProviderSequence,
	lastAdmittedSourceSequence = checkpoint.metrics.lastDurablyAdmittedSequence,
	lastAdmissionOrdinal = checkpoint.metrics.lastAdmissionOrdinal,
	stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
	payload = encodeSensorRuntimeCheckpoint(checkpoint),
	updatedAtMs = updatedAtMs,
)

internal fun sensorProviderCoverage(
	batchingEnabled: Boolean,
	flushOutcome: ProviderFlushOutcome,
): ProviderCoverage = when {
	batchingEnabled && flushOutcome == ProviderFlushOutcome.COMPLETE ->
		ProviderCoverage.FIFO_COMPLETE_AT_FLUSH_CALL
	!batchingEnabled -> ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER
	else -> ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE
}

internal suspend fun SourceEventSink.admitWithBoundedRetry(
	candidate: SourceEvidenceCandidate<*>,
): SourceAdmissionHandoff {
	var result = admit(candidate)
	for (delayMs in ADMISSION_RETRY_DELAYS_MS) {
		if (result !is SourceAdmissionHandoff.RetryableFailure) return result
		delay(delayMs)
		result = admit(candidate)
	}
	return result
}

internal fun appliedState(
	source: SourceKind,
	revision: Long,
	registration: SourceRegistration?,
	status: SourceApplyStatus,
	elapsedRealtimeNanos: Long,
) = AppliedSourcePlan(
	desiredRevision = revision,
	appliedRevision = if (status == SourceApplyStatus.APPLIED || status == SourceApplyStatus.DEGRADED) revision else null,
	source = source,
	sourceInstanceId = registration?.state?.sourceInstanceId?.let(::SourceInstanceId),
	registrationGeneration = registration?.state?.registrationGeneration,
	appliedAtElapsedRealtimeNanos = elapsedRealtimeNanos,
	status = status,
)

private val ADMISSION_RETRY_DELAYS_MS = longArrayOf(10L, 50L, 250L)
private const val SENSOR_RUNTIME_CHECKPOINT_VERSION = 2
private const val SENSOR_RUNTIME_CHECKPOINT_MAGIC = 0x53524350
private const val NULL_LONG = Long.MIN_VALUE
