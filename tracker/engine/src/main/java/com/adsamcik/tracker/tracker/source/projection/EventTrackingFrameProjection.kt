package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.data.collection.PressureReading
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.inject.Inject

/**
 * Event-owned downstream frame projection for consumers while their storage models remain stable.
 *
 * This projection is deliberately single-source: it never implies that step and pressure
 * observations are simultaneous and it never polls or clears a hardware producer window.
 */
class EventTrackingFrameProjection @Inject constructor() : Projection {
	override val id: String = ID
	override val version: Int = VERSION

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		val logicalTrackingId = event.evidence.logicalTrackingId?.value ?: return
		val cycle = event.toEventTrackingFrame() ?: return
		context.recordOutbox(
			ProjectionOutboxEffect(
				stableId = "$ID:${event.eventId.value}",
				kind = OUTBOX_KIND,
				payloadVersion = EventTrackingFrameEffectCodec.VERSION,
				payload = EventTrackingFrameEffectCodec.encode(logicalTrackingId, cycle),
			),
		)
	}

	companion object {
		const val ID = "event-tracking-frame"
		const val VERSION = 2
		const val OUTBOX_KIND = "event-tracking-frame-v1"
	}
}

internal fun AdmittedSourceEvent<out SourcePayload>.toEventTrackingFrame(): TrackingCycle? {
	val timestampMs = evidence.wallTimeMs ?: evidence.acquiredAtMs
	val persistenceId = "source-event:${eventId.value}"
	return when (val payload = evidence.payload) {
		is StepCounterWindowPayload -> {
			// TYPE_STEP_COUNTER's first callback establishes the legacy producer baseline and
			// did not emit a cycle. A real counter reset remains observable even at zero.
			val baselineOnly = payload.baselineReset && payload.deltaCount == 0L &&
				payload.firstCumulativeCount == payload.lastCumulativeCount &&
				payload.firstProviderSequence == payload.lastProviderSequence
			if (baselineOnly || (payload.deltaCount == 0L && !payload.baselineReset)) return null
			val isSingleCounterTransition =
				payload.lastProviderSequence == payload.firstProviderSequence + 1L
			// The native payload retains the prior baseline timestamp/sequence as the start
			// of the mathematical delta. Legacy metadata began at the first callback after
			// a producer drain, so the compatibility view starts at this callback for the
			// un-compacted transition emitted by StepSourceRuntime.
			val legacyWindowStart = if (isSingleCounterTransition) {
				payload.windowEndElapsedRealtimeNanos
			} else {
				payload.windowStartElapsedRealtimeNanos
			}
			val legacyFirstSequence = if (isSingleCounterTransition) {
				payload.lastProviderSequence
			} else {
				payload.firstProviderSequence
			}
			TrackingCycle(
				timestampMs = timestampMs,
				elapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
				stepDelta = payload.deltaCount.toLegacyInt(),
				totalStepsSinceBoot = payload.lastCumulativeCount,
				stepSensorValueStart = payload.firstCumulativeCount.toLegacyInt(),
				stepSensorValueEnd = payload.lastCumulativeCount.toLegacyInt(),
				stepSensorReset = payload.baselineReset,
				stepWindowStartElapsedRealtimeNanos = legacyWindowStart,
				stepWindowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
				stepSourceFirstSequence = legacyFirstSequence,
				stepSourceLastSequence = payload.lastProviderSequence,
				persistenceSignalId = persistenceId,
			)
		}
		is PressureWindowPayload -> {
			if (payload.sampleCount <= 0) return null
			val mean = payload.meanHectopascals.toFloat()
			val variance = if (payload.sampleCount > 1) {
				payload.sumSquaredDeviations / (payload.sampleCount - 1)
			} else {
				0.0
			}
			TrackingCycle(
				timestampMs = timestampMs,
				elapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
				pressure = PressureReading(
					pressureHpa = mean,
					altitudeM = BarometricAltitudeFormula.pressureToAltitudeM(mean)?.toFloat() ?: Float.NaN,
					sampleCount = payload.sampleCount,
					minPressureHpa = payload.minimumHectopascals,
					maxPressureHpa = payload.maximumHectopascals,
					standardDeviationHpa = kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat(),
					windowStartElapsedRealtimeNanos = payload.windowStartElapsedRealtimeNanos,
					windowEndElapsedRealtimeNanos = payload.windowEndElapsedRealtimeNanos,
					sourceFirstSequence = payload.firstProviderSequence,
					sourceLastSequence = payload.lastProviderSequence,
				),
				persistenceSignalId = persistenceId,
			)
		}
		else -> null
	}
}

private fun Long.toLegacyInt(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** Stable, versioned encoding for the cross-transaction compatibility effect. */
internal object EventTrackingFrameEffectCodec {
	const val VERSION = 1
	private const val KIND_STEPS = 1
	private const val KIND_PRESSURE = 2

	fun encode(logicalTrackingId: String, cycle: TrackingCycle): ByteArray = ByteArrayOutputStream().use { bytes ->
		require(logicalTrackingId.isNotBlank())
		DataOutputStream(bytes).use { output ->
			output.writeInt(VERSION)
			output.writeUTF(logicalTrackingId)
			output.writeInt(if (cycle.pressure != null) KIND_PRESSURE else KIND_STEPS)
			output.writeLong(cycle.timestampMs)
			output.writeLong(cycle.elapsedRealtimeNanos)
			output.writeUTF(cycle.persistenceSignalId)
			val pressure = cycle.pressure
			if (pressure != null) {
				output.writeFloat(pressure.pressureHpa)
				output.writeFloat(pressure.altitudeM)
				output.writeInt(pressure.sampleCount)
				output.writeFloat(pressure.minPressureHpa)
				output.writeFloat(pressure.maxPressureHpa)
				output.writeFloat(pressure.standardDeviationHpa)
				output.writeLong(requireNotNull(pressure.windowStartElapsedRealtimeNanos))
				output.writeLong(requireNotNull(pressure.windowEndElapsedRealtimeNanos))
				output.writeLong(requireNotNull(pressure.sourceFirstSequence))
				output.writeLong(requireNotNull(pressure.sourceLastSequence))
			} else {
				output.writeInt(requireNotNull(cycle.stepDelta))
				output.writeLong(requireNotNull(cycle.totalStepsSinceBoot))
				output.writeInt(cycle.stepSensorValueStart)
				output.writeInt(cycle.stepSensorValueEnd)
				output.writeBoolean(cycle.stepSensorReset)
				output.writeLong(requireNotNull(cycle.stepWindowStartElapsedRealtimeNanos))
				output.writeLong(requireNotNull(cycle.stepWindowEndElapsedRealtimeNanos))
				output.writeLong(requireNotNull(cycle.stepSourceFirstSequence))
				output.writeLong(requireNotNull(cycle.stepSourceLastSequence))
			}
		}
		bytes.toByteArray()
	}

	fun decode(payload: ByteArray, payloadVersion: Int): EventTrackingFrameDelivery {
		require(payloadVersion == VERSION) { "Unsupported event-frame effect version $payloadVersion" }
		return DataInputStream(ByteArrayInputStream(payload)).use { input ->
			require(input.readInt() == VERSION)
			val logicalTrackingId = input.readUTF()
			val kind = input.readInt()
			val timestamp = input.readLong()
			val elapsed = input.readLong()
			val persistenceId = input.readUTF()
			val cycle = when (kind) {
				KIND_STEPS -> TrackingCycle(
					timestampMs = timestamp,
					elapsedRealtimeNanos = elapsed,
					stepDelta = input.readInt(),
					totalStepsSinceBoot = input.readLong(),
					stepSensorValueStart = input.readInt(),
					stepSensorValueEnd = input.readInt(),
					stepSensorReset = input.readBoolean(),
					stepWindowStartElapsedRealtimeNanos = input.readLong(),
					stepWindowEndElapsedRealtimeNanos = input.readLong(),
					stepSourceFirstSequence = input.readLong(),
					stepSourceLastSequence = input.readLong(),
					persistenceSignalId = persistenceId,
				)
				KIND_PRESSURE -> TrackingCycle(
					timestampMs = timestamp,
					elapsedRealtimeNanos = elapsed,
					pressure = PressureReading(
						pressureHpa = input.readFloat(),
						altitudeM = input.readFloat(),
						sampleCount = input.readInt(),
						minPressureHpa = input.readFloat(),
						maxPressureHpa = input.readFloat(),
						standardDeviationHpa = input.readFloat(),
						windowStartElapsedRealtimeNanos = input.readLong(),
						windowEndElapsedRealtimeNanos = input.readLong(),
						sourceFirstSequence = input.readLong(),
						sourceLastSequence = input.readLong(),
					),
					persistenceSignalId = persistenceId,
				)
			else -> error("Unknown event-frame effect kind $kind")
			}
			require(input.available() == 0) { "Trailing event-frame effect bytes" }
			EventTrackingFrameDelivery(logicalTrackingId, cycle)
		}
	}
}

internal data class EventTrackingFrameDelivery(
	val logicalTrackingId: String,
	val cycle: TrackingCycle,
)
