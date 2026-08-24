package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.tracker.source.model.AppliedSourcePlan
import com.adsamcik.tracker.tracker.source.model.SourceApplyStatus
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
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
			val currentSequence = lastDurablyAdmittedSequence
			if (currentSequence == null || sequence > currentSequence ||
				(sequence == currentSequence && ordinal > (lastAdmissionOrdinal ?: Long.MIN_VALUE))
			) {
				lastDurablyAdmittedSequence = sequence
				lastAdmissionOrdinal = ordinal
			}
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
	val causalOrderElapsedRealtimeNanos: Long = 0L,
)

/**
 * Closed, precomputed state carried to durable ingress. Room supplies the admission ordinal while
 * committing this value; no source callback or arbitrary work executes inside the transaction.
 */
class SensorAdmissionCheckpoint internal constructor(
	internal val source: SourceKind,
	internal val ownerScope: String,
	internal val sourceInstanceId: String,
	internal val clockDomainId: String,
	internal val registrationGeneration: Long,
	internal val providerSequenceThrough: Long,
	internal val lifecycle: RuntimeCheckpointLifecycle,
	internal val failedAdmissionCount: Long,
	internal val unresolvedSequenceStart: Long?,
	internal val unresolvedSequenceEndInclusive: Long?,
	gapClassifications: Set<RuntimeGapClassification>,
	internal val componentStateVersion: Int,
	componentPayload: ByteArray,
	internal val updatedAtMs: Long,
) {
	internal val gapClassifications: Set<RuntimeGapClassification> = gapClassifications.toSet()
	internal val componentPayload: ByteArray = componentPayload.copyOf()

	init {
		require(source == SourceKind.STEPS || source == SourceKind.PRESSURE)
		require(ownerScope == "source-broker:${source.stableCode}")
		require(sourceInstanceId.isNotBlank())
		require(clockDomainId.isNotBlank())
		require(registrationGeneration > 0L)
		require(providerSequenceThrough > 0L)
		require(failedAdmissionCount >= 0L)
		require((unresolvedSequenceStart == null) == (unresolvedSequenceEndInclusive == null))
		require(unresolvedSequenceStart == null ||
			requireNotNull(unresolvedSequenceStart) <= requireNotNull(unresolvedSequenceEndInclusive))
		require(componentStateVersion > 0)
		require(updatedAtMs >= 0L)
	}

	internal fun toRuntimeState(
		admissionOrdinal: Long,
		causalOrderElapsedRealtimeNanos: Long = 0L,
	): SourceRuntimeStateEntity {
		require(admissionOrdinal > 0L)
		require(causalOrderElapsedRealtimeNanos >= 0L)
		val checkpoint = SensorRuntimeCheckpoint(
			lifecycle = lifecycle,
			metrics = RuntimeAdmissionSnapshot(
				lastDurablyAdmittedSequence = providerSequenceThrough,
				lastAdmissionOrdinal = admissionOrdinal,
				failedAdmissionCount = failedAdmissionCount,
				unresolvedSequenceStart = unresolvedSequenceStart,
				unresolvedSequenceEndInclusive = unresolvedSequenceEndInclusive,
				gapClassifications = gapClassifications,
			),
			componentStateVersion = componentStateVersion,
			componentPayload = componentPayload,
			causalOrderElapsedRealtimeNanos = causalOrderElapsedRealtimeNanos,
		)
		return SourceRuntimeStateEntity(
			sourceKind = source.stableCode,
			ownerScope = ownerScope,
			sourceInstanceId = sourceInstanceId,
			clockDomainId = clockDomainId,
			registrationGeneration = registrationGeneration,
			lastProviderSequence = providerSequenceThrough,
			lastAdmittedSourceSequence = providerSequenceThrough,
			lastAdmissionOrdinal = admissionOrdinal,
			stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
			payload = encodeSensorRuntimeCheckpoint(checkpoint),
			updatedAtMs = updatedAtMs,
		)
	}
}

internal fun SourceRegistration.sensorAdmissionCheckpoint(
	providerSequenceThrough: Long,
	checkpoint: SensorRuntimeCheckpoint,
	updatedAtMs: Long,
): SensorAdmissionCheckpoint = SensorAdmissionCheckpoint(
	source = SourceKind.entries.single { it.stableCode == state.sourceKind },
	ownerScope = ownerScope,
	sourceInstanceId = state.sourceInstanceId,
	clockDomainId = state.clockDomainId,
	registrationGeneration = state.registrationGeneration,
	providerSequenceThrough = providerSequenceThrough,
	lifecycle = checkpoint.lifecycle,
	failedAdmissionCount = checkpoint.metrics.failedAdmissionCount,
	unresolvedSequenceStart = checkpoint.metrics.unresolvedSequenceStart,
	unresolvedSequenceEndInclusive = checkpoint.metrics.unresolvedSequenceEndInclusive,
	gapClassifications = checkpoint.metrics.gapClassifications.toSet(),
	componentStateVersion = checkpoint.componentStateVersion,
	componentPayload = checkpoint.componentPayload,
	updatedAtMs = updatedAtMs,
)

internal fun decodeSensorRuntimeCheckpoint(
	state: SourceRuntimeStateEntity?,
	legacyComponentStateVersion: Int,
): SensorRuntimeCheckpoint? {
	state ?: return null
	if (state.stateVersion != SENSOR_RUNTIME_CHECKPOINT_VERSION &&
		state.stateVersion != SENSOR_RUNTIME_CHECKPOINT_VERSION_V2
	) {
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
			val causalOrderElapsedRealtimeNanos = if (
				state.stateVersion == SENSOR_RUNTIME_CHECKPOINT_VERSION
			) input.readLong() else 0L
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
			val checkpoint = SensorRuntimeCheckpoint(
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
				causalOrderElapsedRealtimeNanos,
			)
			require(checkpoint.causalOrderElapsedRealtimeNanos >= 0L)
			require(checkpoint.metrics.lastDurablyAdmittedSequence ==
				state.lastAdmittedSourceSequence)
			require(checkpoint.metrics.lastAdmissionOrdinal == state.lastAdmissionOrdinal)
			checkpoint
		}
	}.getOrNull()
}

/**
 * Merges two checkpoints without using wall time as a correctness clock. Lifecycle and component
 * state follow their boot-scoped causal order; admission and gap facts are monotonic joins and are
 * always re-encoded with their indexed columns.
 */
internal fun mergeSensorRuntimeStates(
	current: SourceRuntimeStateEntity?,
	incoming: SourceRuntimeStateEntity,
	legacyComponentStateVersion: Int,
): SourceRuntimeStateEntity {
	val canonicalIncoming = canonicalSensorRuntimeState(incoming, legacyComponentStateVersion)
	val incomingCheckpoint = requireNotNull(
		decodeSensorRuntimeCheckpoint(canonicalIncoming, legacyComponentStateVersion),
	)
	current ?: return canonicalIncoming
	require(current.sourceKind == incoming.sourceKind && current.ownerScope == incoming.ownerScope)
	if (incoming.registrationGeneration < current.registrationGeneration) return current
	if (incoming.registrationGeneration > current.registrationGeneration) {
		return canonicalIncoming
	}
	require(current.sourceInstanceId == incoming.sourceInstanceId &&
		current.clockDomainId == incoming.clockDomainId
	) { "Sensor checkpoint identity changed inside one registration generation" }

	val currentCheckpoint = requireNotNull(
		decodeSensorRuntimeCheckpoint(current, incomingCheckpoint.componentStateVersion),
	) { "Current sensor checkpoint is corrupt" }
	val stateCheckpoint = selectSensorState(
		currentEntity = current,
		currentCheckpoint = currentCheckpoint,
		incomingEntity = incoming,
		incomingCheckpoint = incomingCheckpoint,
	)
	val mergedMetrics = mergeRuntimeAdmissionSnapshots(
		currentCheckpoint.metrics,
		incomingCheckpoint.metrics,
	)
	val mergedCheckpoint = stateCheckpoint.copy(metrics = mergedMetrics)
	return incoming.copy(
		lastProviderSequence = maxOf(current.lastProviderSequence, incoming.lastProviderSequence),
		lastAdmittedSourceSequence = mergedMetrics.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = mergedMetrics.lastAdmissionOrdinal,
		stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
		payload = encodeSensorRuntimeCheckpoint(mergedCheckpoint),
		updatedAtMs = maxOf(current.updatedAtMs, incoming.updatedAtMs),
	)
}

private fun canonicalSensorRuntimeState(
	state: SourceRuntimeStateEntity,
	legacyComponentStateVersion: Int,
): SourceRuntimeStateEntity {
	val checkpoint = requireNotNull(decodeSensorRuntimeCheckpoint(state, legacyComponentStateVersion)) {
		"Sensor checkpoint is corrupt"
	}
	return state.copy(
		lastAdmittedSourceSequence = checkpoint.metrics.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = checkpoint.metrics.lastAdmissionOrdinal,
		stateVersion = SENSOR_RUNTIME_CHECKPOINT_VERSION,
		payload = encodeSensorRuntimeCheckpoint(checkpoint),
	)
}

private fun selectSensorState(
	currentEntity: SourceRuntimeStateEntity,
	currentCheckpoint: SensorRuntimeCheckpoint,
	incomingEntity: SourceRuntimeStateEntity,
	incomingCheckpoint: SensorRuntimeCheckpoint,
): SensorRuntimeCheckpoint {
	val causalComparison = incomingCheckpoint.causalOrderElapsedRealtimeNanos.compareTo(
		currentCheckpoint.causalOrderElapsedRealtimeNanos,
	)
	if (causalComparison != 0) return if (causalComparison > 0) incomingCheckpoint else currentCheckpoint

	val lifecycleComparison = lifecyclePrecedence(incomingCheckpoint.lifecycle).compareTo(
		lifecyclePrecedence(currentCheckpoint.lifecycle),
	)
	if (lifecycleComparison != 0) {
		return if (lifecycleComparison > 0) incomingCheckpoint else currentCheckpoint
	}
	val providerComparison = incomingEntity.lastProviderSequence.compareTo(currentEntity.lastProviderSequence)
	if (providerComparison != 0) return if (providerComparison > 0) incomingCheckpoint else currentCheckpoint
	val componentVersionComparison = incomingCheckpoint.componentStateVersion.compareTo(
		currentCheckpoint.componentStateVersion,
	)
	if (componentVersionComparison != 0) {
		return if (componentVersionComparison > 0) incomingCheckpoint else currentCheckpoint
	}
	return if (compareBytes(incomingCheckpoint.componentPayload, currentCheckpoint.componentPayload) > 0) {
		incomingCheckpoint
	} else {
		currentCheckpoint
	}
}

private fun lifecyclePrecedence(lifecycle: RuntimeCheckpointLifecycle): Int = when (lifecycle) {
	RuntimeCheckpointLifecycle.ACTIVE -> 0
	RuntimeCheckpointLifecycle.TIMED_OUT -> 1
	RuntimeCheckpointLifecycle.QUIESCED -> 2
}

private fun compareBytes(first: ByteArray, second: ByteArray): Int {
	val sharedSize = minOf(first.size, second.size)
	for (index in 0 until sharedSize) {
		val comparison = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
		if (comparison != 0) return comparison
	}
	return first.size.compareTo(second.size)
}

private fun mergeRuntimeAdmissionSnapshots(
	first: RuntimeAdmissionSnapshot,
	second: RuntimeAdmissionSnapshot,
): RuntimeAdmissionSnapshot {
	val durable = laterDurablePair(first, second)
	return RuntimeAdmissionSnapshot(
		lastDurablyAdmittedSequence = durable.lastDurablyAdmittedSequence,
		lastAdmissionOrdinal = durable.lastAdmissionOrdinal,
		// These are absolute cumulative snapshots from one serialized runtime lane. Summing two
		// retries would count the same failures twice; the monotonic join is therefore max.
		failedAdmissionCount = maxOf(first.failedAdmissionCount, second.failedAdmissionCount),
		unresolvedSequenceStart = minimumNullable(
			first.unresolvedSequenceStart,
			second.unresolvedSequenceStart,
		),
		unresolvedSequenceEndInclusive = maximumNullable(
			first.unresolvedSequenceEndInclusive,
			second.unresolvedSequenceEndInclusive,
		),
		gapClassifications = first.gapClassifications + second.gapClassifications,
	)
}

private fun laterDurablePair(
	first: RuntimeAdmissionSnapshot,
	second: RuntimeAdmissionSnapshot,
): RuntimeAdmissionSnapshot {
	val sequenceComparison = nullableLong(first.lastDurablyAdmittedSequence).compareTo(
		nullableLong(second.lastDurablyAdmittedSequence),
	)
	if (sequenceComparison != 0) return if (sequenceComparison > 0) first else second
	val ordinalComparison = nullableLong(first.lastAdmissionOrdinal).compareTo(
		nullableLong(second.lastAdmissionOrdinal),
	)
	return if (ordinalComparison >= 0) first else second
}

private fun nullableLong(value: Long?): Long = value ?: Long.MIN_VALUE

private fun maximumNullable(first: Long?, second: Long?): Long? = when {
	first == null -> second
	second == null -> first
	else -> maxOf(first, second)
}

private fun minimumNullable(first: Long?, second: Long?): Long? = when {
	first == null -> second
	second == null -> first
	else -> minOf(first, second)
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
			output.writeLong(checkpoint.causalOrderElapsedRealtimeNanos)
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

internal fun SensorRuntimeCheckpoint.withFallbackCausalOrder(
	fallbackElapsedRealtimeNanos: Long,
): SensorRuntimeCheckpoint {
	require(fallbackElapsedRealtimeNanos >= 0L)
	return if (causalOrderElapsedRealtimeNanos > 0L) this else copy(
		causalOrderElapsedRealtimeNanos = fallbackElapsedRealtimeNanos,
	)
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
	payload = encodeSensorRuntimeCheckpoint(
		checkpoint.withFallbackCausalOrder(SystemClock.elapsedRealtimeNanos()),
	),
	updatedAtMs = updatedAtMs,
)

internal fun sensorProviderCoverage(
	batchingEnabled: Boolean,
	@Suppress("UNUSED_PARAMETER") flushOutcome: ProviderFlushOutcome,
): ProviderCoverage = if (batchingEnabled) {
	// SensorManager.flush() only acknowledges delivery of samples still present in the
	// hardware FIFO. It cannot prove that earlier samples were never overwritten.
	ProviderCoverage.PROVIDER_COMPLETENESS_UNOBSERVABLE
} else {
	ProviderCoverage.CALLBACKS_ENTERED_BEFORE_BARRIER
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

internal const val SENSOR_RUNTIME_CHECKPOINT_VERSION = 3
private const val SENSOR_RUNTIME_CHECKPOINT_VERSION_V2 = 2
private const val SENSOR_RUNTIME_CHECKPOINT_MAGIC = 0x53524350
private const val NULL_LONG = Long.MIN_VALUE
