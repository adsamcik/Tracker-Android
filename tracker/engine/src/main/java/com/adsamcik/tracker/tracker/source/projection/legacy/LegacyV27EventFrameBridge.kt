package com.adsamcik.tracker.tracker.source.projection.legacy

import com.adsamcik.tracker.shared.base.database.dao.PressureSampleDao
import com.adsamcik.tracker.shared.base.database.dao.StepIntervalDao
import com.adsamcik.tracker.shared.base.database.dao.SourceDestinationOwnerDao
import com.adsamcik.tracker.shared.base.database.data.ObservationStampColumns
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionOutboxEntity
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameEffectCodec
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameProjection
import com.adsamcik.tracker.tracker.source.projection.releasedV1EventTrackingFrame
import javax.inject.Inject

/**
 * Frozen typed-destination bridge for the `event-tracking-frame` generation shipped with v27.
 *
 * The released projection routed Steps and Pressure through an in-process tracking frame. During
 * v28 startup recovery there may be no live service that can consume that frame, so this bridge
 * writes the two exact typed destinations itself. It deliberately does not act as a new live
 * materializer and must only be called for the immutable released-v27 cutoff.
 */
class LegacyV27EventFrameBridge @Inject constructor(
	private val stepIntervalDao: StepIntervalDao,
	private val pressureSampleDao: PressureSampleDao,
	private val sourceDestinationOwnerDao: SourceDestinationOwnerDao,
) {
	suspend fun bridge(
		wal: SourceEventWalEntity,
		payload: SourcePayload,
	): LegacyV27EventFrameBridgeResult {
		require(wal.payloadVersion == RELEASED_PAYLOAD_VERSION) {
			"Unsupported released-v27 payload version ${wal.payloadVersion}"
		}
		require(payload.source.stableCode == wal.sourceKind) {
			"Decoded payload source does not match its WAL row"
		}
		return when (payload) {
			is StepCounterWindowPayload -> bridgeSteps(wal, payload)
			is PressureWindowPayload -> bridgePressure(wal, payload)
			else -> LegacyV27EventFrameBridgeResult.NoFact
		}
	}

	/**
	 * Recover a retained released-v27 outbox effect whose raw WAL row was already pruned.
	 *
	 * The v1 effect contains the typed metric/window identity but not the source clock domain,
	 * original receipt clock or wall-time uncertainty. Those audit fields remain explicitly
	 * partial instead of being reconstructed from the current process or boot.
	 */
	suspend fun bridge(outbox: SourceProjectionOutboxEntity): LegacyV27EventFrameBridgeResult {
		require(outbox.projectionId == EventTrackingFrameProjection.ID)
		require(outbox.projectionVersion == RELEASED_PROJECTION_VERSION)
		require(outbox.effectKind == EventTrackingFrameProjection.OUTBOX_KIND)
		val cycle = EventTrackingFrameEffectCodec.decode(outbox.payload, outbox.payloadVersion).cycle
		val sourceSignalId = cycle.persistenceSignalId
		require(sourceSignalId.startsWith(SOURCE_EVENT_SIGNAL_PREFIX) &&
			sourceSignalId.length > SOURCE_EVENT_SIGNAL_PREFIX.length) {
			"Released-v27 event-frame effect has an invalid persistence identity"
		}
		return when {
			cycle.pressure != null -> bridgeOutboxPressure(cycle, sourceSignalId)
			cycle.stepDelta != null -> bridgeOutboxSteps(cycle, sourceSignalId)
			else -> LegacyV27EventFrameBridgeResult.NoFact
		}
	}

	private suspend fun bridgeSteps(
		wal: SourceEventWalEntity,
		payload: StepCounterWindowPayload,
	): LegacyV27EventFrameBridgeResult {
		val wallTimeMs = wal.wallTimeMs ?: wal.acquiredAtMs
		val cycle = releasedV1EventTrackingFrame(wal.eventId, wallTimeMs, payload)
			?: return LegacyV27EventFrameBridgeResult.NoFact
		val windowStartElapsedRealtimeNanos = requireNotNull(cycle.stepWindowStartElapsedRealtimeNanos)
		val windowEndElapsedRealtimeNanos = requireNotNull(cycle.stepWindowEndElapsedRealtimeNanos)
		val durationMs = ((windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos)
			.coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		val sourceSignalId = wal.sourceSignalId()
		val candidate = StepInterval(
			startTimeMs = wallTimeMs - durationMs,
			endTimeMs = wallTimeMs,
			stepCount = requireNotNull(cycle.stepDelta),
			sensorValueStart = cycle.stepSensorValueStart,
			sensorValueEnd = cycle.stepSensorValueEnd,
			sensorReset = cycle.stepSensorReset,
			createdAt = wallTimeMs,
			sourceSignalId = sourceSignalId,
			observationStamp = wal.observationStamp(
				fallbackFirstElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
				sourceFirstSequence = requireNotNull(cycle.stepSourceFirstSequence),
				sourceLastSequence = requireNotNull(cycle.stepSourceLastSequence),
				bootClockDomainId = payload.bootClockDomainId,
			),
		)
		return insertOrVerify(
			destination = LegacyV27EventFrameDestination.STEP_INTERVAL,
			sourceSignalId = sourceSignalId,
			candidate = candidate,
			insert = stepIntervalDao::insert,
			load = stepIntervalDao::getBySourceSignalId,
			auditProvenance = LegacyV27BridgeAuditProvenance.WAL_ENRICHED,
			exactlyMatches = { existing ->
				existing.hasSameStepFact(candidate) && (
					existing.observationStamp == candidate.observationStamp ||
						existing.observationStamp.matchesReleasedV27DeliveredStamp(
							cycle = cycle,
							sourceElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
							sourceFirstElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
							sourceSequence = requireNotNull(cycle.stepSourceLastSequence),
							sourceFirstSequence = requireNotNull(cycle.stepSourceFirstSequence),
						)
					)
			},
		)
	}

	private suspend fun bridgePressure(
		wal: SourceEventWalEntity,
		payload: PressureWindowPayload,
	): LegacyV27EventFrameBridgeResult {
		val wallTimeMs = wal.wallTimeMs ?: wal.acquiredAtMs
		val cycle = releasedV1EventTrackingFrame(wal.eventId, wallTimeMs, payload)
			?: return LegacyV27EventFrameBridgeResult.NoFact
		val pressure = requireNotNull(cycle.pressure)
		val sourceSignalId = wal.sourceSignalId()
		val candidate = PressureSample(
			timeMs = wallTimeMs,
			elapsedRealtimeNanos = wal.observedElapsedNanos,
			pressureHpa = pressure.pressureHpa,
			// Released-v27 compatibility only: this is an uncalibrated standard-atmosphere
			// estimate and must not be promoted as a new calibrated vertical metric.
			altitudeM = pressure.altitudeM,
			bucketId = null,
			createdAt = wallTimeMs,
			sourceSignalId = sourceSignalId,
			sampleCount = pressure.sampleCount,
			minPressureHpa = pressure.minPressureHpa,
			maxPressureHpa = pressure.maxPressureHpa,
			standardDeviationHpa = pressure.standardDeviationHpa,
			windowStartElapsedRealtimeNanos = pressure.windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = pressure.windowEndElapsedRealtimeNanos,
			observationStamp = wal.observationStamp(
				fallbackFirstElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
				sourceFirstSequence = payload.firstProviderSequence,
				sourceLastSequence = payload.lastProviderSequence,
				bootClockDomainId = wal.clockDomainId,
			),
		)
		return insertOrVerify(
			destination = LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			sourceSignalId = sourceSignalId,
			candidate = candidate,
			insert = pressureSampleDao::insert,
			load = pressureSampleDao::getBySourceSignalId,
			auditProvenance = LegacyV27BridgeAuditProvenance.WAL_ENRICHED,
			exactlyMatches = { existing ->
				existing.hasSamePressureFact(candidate) && (
					existing.observationStamp == candidate.observationStamp ||
						existing.observationStamp.matchesReleasedV27DeliveredStamp(
							cycle = cycle,
							sourceElapsedRealtimeNanos = requireNotNull(
								pressure.windowEndElapsedRealtimeNanos,
							),
							sourceFirstElapsedRealtimeNanos = requireNotNull(
								pressure.windowStartElapsedRealtimeNanos,
							),
							sourceSequence = requireNotNull(pressure.sourceLastSequence),
							sourceFirstSequence = requireNotNull(pressure.sourceFirstSequence),
						)
					)
			},
		)
	}

	private suspend fun bridgeOutboxSteps(
		cycle: TrackingCycle,
		sourceSignalId: String,
	): LegacyV27EventFrameBridgeResult {
		val windowStartElapsedRealtimeNanos = requireNotNull(cycle.stepWindowStartElapsedRealtimeNanos)
		val windowEndElapsedRealtimeNanos = requireNotNull(cycle.stepWindowEndElapsedRealtimeNanos)
		val sourceFirstSequence = requireNotNull(cycle.stepSourceFirstSequence)
		val sourceLastSequence = requireNotNull(cycle.stepSourceLastSequence)
		val durationMs = ((windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos)
			.coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
		val candidate = StepInterval(
			startTimeMs = cycle.timestampMs - durationMs,
			endTimeMs = cycle.timestampMs,
			stepCount = requireNotNull(cycle.stepDelta),
			sensorValueStart = cycle.stepSensorValueStart,
			sensorValueEnd = cycle.stepSensorValueEnd,
			sensorReset = cycle.stepSensorReset,
			createdAt = cycle.timestampMs,
			sourceSignalId = sourceSignalId,
			observationStamp = partialOutboxStamp(
				cycle = cycle,
				sourceElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
				sourceFirstElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
				sourceSequence = sourceLastSequence,
				sourceFirstSequence = sourceFirstSequence,
			),
		)
		return insertOrVerify(
			destination = LegacyV27EventFrameDestination.STEP_INTERVAL,
			sourceSignalId = sourceSignalId,
			candidate = candidate,
			insert = stepIntervalDao::insert,
			load = stepIntervalDao::getBySourceSignalId,
			auditProvenance = LegacyV27BridgeAuditProvenance.LEGACY_OUTBOX_PARTIAL,
			exactlyMatches = { existing ->
				existing.hasSameStepFact(candidate) && (
					existing.observationStamp == candidate.observationStamp ||
						existing.observationStamp.matchesReleasedV27DeliveredStamp(
							cycle,
							windowEndElapsedRealtimeNanos,
							windowStartElapsedRealtimeNanos,
							sourceLastSequence,
							sourceFirstSequence,
						)
					)
			},
		)
	}

	private suspend fun bridgeOutboxPressure(
		cycle: TrackingCycle,
		sourceSignalId: String,
	): LegacyV27EventFrameBridgeResult {
		val pressure = requireNotNull(cycle.pressure)
		val windowStartElapsedRealtimeNanos = requireNotNull(pressure.windowStartElapsedRealtimeNanos)
		val windowEndElapsedRealtimeNanos = requireNotNull(pressure.windowEndElapsedRealtimeNanos)
		val sourceFirstSequence = requireNotNull(pressure.sourceFirstSequence)
		val sourceLastSequence = requireNotNull(pressure.sourceLastSequence)
		val candidate = PressureSample(
			timeMs = cycle.timestampMs,
			elapsedRealtimeNanos = cycle.elapsedRealtimeNanos,
			pressureHpa = pressure.pressureHpa,
			altitudeM = pressure.altitudeM,
			bucketId = null,
			createdAt = cycle.timestampMs,
			sourceSignalId = sourceSignalId,
			sampleCount = pressure.sampleCount,
			minPressureHpa = pressure.minPressureHpa,
			maxPressureHpa = pressure.maxPressureHpa,
			standardDeviationHpa = pressure.standardDeviationHpa,
			windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
			observationStamp = partialOutboxStamp(
				cycle = cycle,
				sourceElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
				sourceFirstElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
				sourceSequence = sourceLastSequence,
				sourceFirstSequence = sourceFirstSequence,
			),
		)
		return insertOrVerify(
			destination = LegacyV27EventFrameDestination.PRESSURE_SAMPLE,
			sourceSignalId = sourceSignalId,
			candidate = candidate,
			insert = pressureSampleDao::insert,
			load = pressureSampleDao::getBySourceSignalId,
			auditProvenance = LegacyV27BridgeAuditProvenance.LEGACY_OUTBOX_PARTIAL,
			exactlyMatches = { existing ->
				existing.hasSamePressureFact(candidate) && (
					existing.observationStamp == candidate.observationStamp ||
						existing.observationStamp.matchesReleasedV27DeliveredStamp(
							cycle,
							windowEndElapsedRealtimeNanos,
							windowStartElapsedRealtimeNanos,
							sourceLastSequence,
							sourceFirstSequence,
						)
					)
			},
		)
	}

	private suspend fun <T> insertOrVerify(
		destination: LegacyV27EventFrameDestination,
		sourceSignalId: String,
		candidate: T,
		insert: suspend (T) -> Long,
		load: suspend (String) -> T?,
		auditProvenance: LegacyV27BridgeAuditProvenance,
		exactlyMatches: (T) -> Boolean,
	): LegacyV27EventFrameBridgeResult {
		if (destination == LegacyV27EventFrameDestination.STEP_INTERVAL) {
			check(sourceDestinationOwnerDao.isExactOwner(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_LEGACY_GENERATION,
			)) { "Legacy Steps destination ownership changed before v27 bridge commit" }
		}
		val rowId = insert(candidate)
		val existing = load(sourceSignalId)
		if (existing != null && exactlyMatches(existing)) {
			return LegacyV27EventFrameBridgeResult.Applied(
					destination = destination,
					inserted = rowId != INSERT_IGNORED,
					auditProvenance = auditProvenance,
			)
		}
		return LegacyV27EventFrameBridgeResult.IdentityCollision(destination, sourceSignalId)
	}

	private fun SourceEventWalEntity.observationStamp(
		fallbackFirstElapsedRealtimeNanos: Long,
		sourceFirstSequence: Long,
		sourceLastSequence: Long,
		bootClockDomainId: String,
	): ObservationStampColumns {
		val sourceAgeMs = ((receivedElapsedNanos - observedElapsedNanos).coerceAtLeast(0L) /
			NANOS_PER_MILLISECOND)
		return ObservationStampColumns(
			sourceTimeMs = wallTimeMs,
			sourceElapsedRealtimeNanos = observedElapsedNanos,
			sourceFirstElapsedRealtimeNanos = observedIntervalStartNanos
				?: fallbackFirstElapsedRealtimeNanos,
			receivedTimeMs = acquiredAtMs,
			receivedElapsedRealtimeNanos = receivedElapsedNanos,
			sourceSequence = sourceLastSequence,
			sourceFirstSequence = sourceFirstSequence,
			clockDomainId = clockDomainId,
			bootClockDomainId = bootClockDomainId,
			sourceAgeMs = sourceAgeMs,
			timeUncertaintyMs = wallTimeUncertaintyMs,
		)
	}

	private fun SourceEventWalEntity.sourceSignalId(): String = "source-event:$eventId"

	private fun partialOutboxStamp(
		cycle: TrackingCycle,
		sourceElapsedRealtimeNanos: Long,
		sourceFirstElapsedRealtimeNanos: Long,
		sourceSequence: Long,
		sourceFirstSequence: Long,
	): ObservationStampColumns = ObservationStampColumns(
		sourceTimeMs = null,
		sourceElapsedRealtimeNanos = sourceElapsedRealtimeNanos,
		sourceFirstElapsedRealtimeNanos = sourceFirstElapsedRealtimeNanos,
		receivedTimeMs = cycle.timestampMs,
		// This is the cycle clock carried by the v1 effect, not proof of the original WAL receipt.
		receivedElapsedRealtimeNanos = cycle.elapsedRealtimeNanos,
		sourceSequence = sourceSequence,
		sourceFirstSequence = sourceFirstSequence,
		clockDomainId = LEGACY_UNKNOWN,
		bootClockDomainId = LEGACY_UNKNOWN,
		sourceAgeMs = null,
		timeUncertaintyMs = null,
		capabilityFlags = LEGACY_OUTBOX_PARTIAL_FLAGS,
	)

	private fun ObservationStampColumns.matchesReleasedV27DeliveredStamp(
		cycle: TrackingCycle,
		sourceElapsedRealtimeNanos: Long,
		sourceFirstElapsedRealtimeNanos: Long,
		sourceSequence: Long,
		sourceFirstSequence: Long,
	): Boolean {
		if (clockDomainId.isNullOrBlank() || bootClockDomainId.isNullOrBlank()) return false
		val ageMs = if (cycle.elapsedRealtimeNanos > 0L && sourceElapsedRealtimeNanos > 0L) {
			((cycle.elapsedRealtimeNanos - sourceElapsedRealtimeNanos).coerceAtLeast(0L) /
				NANOS_PER_MILLISECOND)
		} else {
			null
		}
		val released = ObservationStampColumns(
			sourceTimeMs = null,
			sourceElapsedRealtimeNanos = sourceElapsedRealtimeNanos,
			sourceFirstElapsedRealtimeNanos = sourceFirstElapsedRealtimeNanos,
			receivedTimeMs = cycle.timestampMs,
			receivedElapsedRealtimeNanos = cycle.elapsedRealtimeNanos,
			sourceSequence = sourceSequence,
			sourceFirstSequence = sourceFirstSequence,
			clockDomainId = null,
			bootClockDomainId = null,
			sourceAgeMs = ageMs,
			timeUncertaintyMs = ageMs,
		)
		return copy(clockDomainId = null, bootClockDomainId = null) == released
	}

	private fun StepInterval.hasSameStepFact(other: StepInterval): Boolean =
		copy(id = 0L, observationStamp = ObservationStampColumns()) ==
			other.copy(id = 0L, observationStamp = ObservationStampColumns())

	private fun PressureSample.hasSamePressureFact(other: PressureSample): Boolean =
		copy(id = 0L, observationStamp = ObservationStampColumns()) ==
			other.copy(id = 0L, observationStamp = ObservationStampColumns())

	private companion object {
		const val RELEASED_PROJECTION_VERSION = 1
		const val RELEASED_PAYLOAD_VERSION = 1
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val INSERT_IGNORED = -1L
		const val SOURCE_EVENT_SIGNAL_PREFIX = "source-event:"
		const val LEGACY_UNKNOWN = "LEGACY_UNKNOWN"
		const val LEGACY_OUTBOX_PARTIAL_FLAGS =
			"LEGACY_V27_OUTBOX_ONLY,PARTIAL_CLOCK_PROVENANCE"
	}
}

enum class LegacyV27EventFrameDestination {
	STEP_INTERVAL,
	PRESSURE_SAMPLE,
}

enum class LegacyV27BridgeAuditProvenance {
	WAL_ENRICHED,
	LEGACY_OUTBOX_PARTIAL,
}

sealed interface LegacyV27EventFrameBridgeResult {
	data object NoFact : LegacyV27EventFrameBridgeResult

	data class Applied(
		val destination: LegacyV27EventFrameDestination,
		val inserted: Boolean,
		val auditProvenance: LegacyV27BridgeAuditProvenance =
			LegacyV27BridgeAuditProvenance.WAL_ENRICHED,
	) : LegacyV27EventFrameBridgeResult

	data class IdentityCollision(
		val destination: LegacyV27EventFrameDestination,
		val sourceSignalId: String,
	) : LegacyV27EventFrameBridgeResult
}
