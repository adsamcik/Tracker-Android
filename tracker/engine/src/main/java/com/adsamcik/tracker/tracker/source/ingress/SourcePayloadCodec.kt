package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureSensorAccuracy
import com.adsamcik.tracker.tracker.source.model.PressureWindowClosureKind
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptOutcome
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptPayload
import com.adsamcik.tracker.tracker.source.model.expectedPressureSampleCount
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

interface SourcePayloadCodec {
	fun encode(payload: SourcePayload, payloadVersion: Int): EncodedSourcePayload
	fun decode(source: SourceKind, payloadVersion: Int, bytes: ByteArray): SourcePayload
}

data class EncodedSourcePayload(
	val bytes: ByteArray,
	val checksum: String,
)

@Singleton
class DefaultSourcePayloadCodec @Inject constructor() : SourcePayloadCodec {
	override fun encode(payload: SourcePayload, payloadVersion: Int): EncodedSourcePayload {
		require(payloadVersion in MINIMUM_VERSION..CURRENT_VERSION) {
			"Unsupported source payload version $payloadVersion"
		}
		val bytes = ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output -> output.writePayload(payload, payloadVersion) }
			buffer.toByteArray()
		}
		return EncodedSourcePayload(bytes, bytes.sha256())
	}

	override fun decode(source: SourceKind, payloadVersion: Int, bytes: ByteArray): SourcePayload {
		require(payloadVersion in MINIMUM_VERSION..CURRENT_VERSION) {
			"Unsupported source payload version $payloadVersion"
		}
		if (payloadVersion == LEGACY_V27_VERSION) {
			return LegacyV27SourcePayloadDecoder.decode(source, bytes)
		}
		val payload = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
			input.readPayload(payloadVersion).also {
				if (payloadVersion >= PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION) {
					require(input.available() == 0) { "Trailing source-payload version 4 bytes" }
				}
			}
		}
		require(payload.source == source) { "Encoded payload source does not match WAL source" }
		return payload
	}

	private fun DataOutputStream.writePayload(payload: SourcePayload, payloadVersion: Int) {
		when (payload) {
			is LocationFixPayload -> {
				writeInt(TYPE_LOCATION_FIX)
				writeDouble(payload.latitudeDegrees)
				writeDouble(payload.longitudeDegrees)
				writeFloat(payload.horizontalAccuracyMeters)
				writeNullableDouble(payload.altitudeMeters)
				writeNullableFloat(payload.verticalAccuracyMeters)
				writeNullableFloat(payload.speedMetersPerSecond)
				writeNullableFloat(payload.bearingDegrees)
				writeUTF(payload.provider)
			}
			is ActivityTransitionPayload -> {
				writeInt(TYPE_ACTIVITY_TRANSITION)
				writeInt(payload.activityType)
				writeInt(payload.transitionType)
				writeLong(payload.providerElapsedRealtimeNanos)
			}
			is ActivityRecognitionPayload -> {
				writeInt(TYPE_ACTIVITY_RECOGNITION)
				writeInt(payload.activityType)
				writeInt(payload.confidencePercent)
				writeNullableLong(payload.providerElapsedRealtimeNanos)
			}
			is StepCounterWindowPayload -> {
				writeInt(TYPE_STEP_WINDOW)
				writeUTF(payload.bootClockDomainId)
				writeLong(payload.firstCumulativeCount)
				writeLong(payload.lastCumulativeCount)
				writeLong(payload.deltaCount)
				writeLong(payload.windowStartElapsedRealtimeNanos)
				writeLong(payload.windowEndElapsedRealtimeNanos)
				writeLong(payload.firstProviderSequence)
				writeLong(payload.lastProviderSequence)
				if (payloadVersion >= STEP_BOUNDARY_KIND_PAYLOAD_VERSION) {
					require(payload.boundaryKind != StepBoundaryKind.LEGACY_AMBIGUOUS) {
						"Legacy-ambiguous Steps boundaries are decode-only"
					}
					writeInt(payload.boundaryKind.stableWireCode())
				} else {
					writeBoolean(payload.baselineReset)
				}
			}
			is PressureWindowPayload -> {
				if (payloadVersion >= PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION) {
					payload.requireQualifiedPressureShape()
				} else {
					payload.requireLegacyPressureShape()
				}
				writeInt(TYPE_PRESSURE_WINDOW)
				writeInt(payload.sampleCount)
				writeDouble(payload.meanHectopascals)
				writeDouble(payload.sumSquaredDeviations)
				writeFloat(payload.minimumHectopascals)
				writeFloat(payload.maximumHectopascals)
				writeLong(payload.windowStartElapsedRealtimeNanos)
				writeLong(payload.windowEndElapsedRealtimeNanos)
				writeLong(payload.firstProviderSequence)
				writeLong(payload.lastProviderSequence)
				if (payloadVersion >= PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION) {
					writeFloat(requireNotNull(payload.firstHectopascals))
					writeFloat(requireNotNull(payload.lastHectopascals))
					writeNullableDouble(payload.slopeHectopascalsPerSecond)
					writeNullableDouble(payload.rSquared)
					writeInt(payload.sensorAccuracy.stableWireCode())
					writeInt(requireNotNull(payload.effectiveSamplePeriodMicros))
					writeInt(requireNotNull(payload.effectiveMaximumReportLatencyMicros))
					writeLong(requireNotNull(payload.targetWindowDurationNanos))
					writeInt(requireNotNull(payload.expectedSampleCount))
					writeLong(requireNotNull(payload.maximumInterSampleGapNanos))
					writeInt(payload.closureKind.stableWireCode())
				}
			}
			is WifiScanAttemptPayload -> {
				writeInt(TYPE_WIFI_ATTEMPT)
				writeUTF(payload.attemptId)
				writeInt(payload.outcome.ordinal)
				writeNullableInt(payload.resultCount)
				writeNullableLong(payload.resultAgeMs)
			}
			is WifiResultSnapshotPayload -> {
				writeInt(TYPE_WIFI_RESULTS)
				writeInt(payload.accessPoints.size)
				payload.accessPoints.forEach { accessPoint ->
					writeUTF(accessPoint.identifierToken)
					writeInt(accessPoint.frequencyMhz)
					writeInt(accessPoint.signalLevelDbm)
					if (payloadVersion >= WIFI_ITEM_TIME_VERSION) {
						writeNullableLong(accessPoint.providerTimestampNanos)
					}
				}
				writeNullableLong(payload.platformTimestampMs)
				writeNullableLong(payload.resultAgeMs)
			}
			is CellSnapshotPayload -> {
				writeInt(TYPE_CELL_SNAPSHOT)
				writeNullableInt(payload.subscriptionId)
				writeInt(payload.observations.size)
				payload.observations.forEach { observation ->
					writeUTF(observation.identifierToken)
					writeUTF(observation.radioType)
					writeBoolean(observation.registered)
					writeNullableInt(observation.signalLevelDbm)
					writeNullableLong(observation.providerTimestampNanos)
				}
				writeInt(payload.refreshOutcome.ordinal)
			}
			else -> error("Unsupported source payload ${payload::class.java.name}")
		}
	}

	private fun DataInputStream.readPayload(payloadVersion: Int): SourcePayload = when (val type = readInt()) {
		TYPE_LOCATION_FIX -> LocationFixPayload(
			latitudeDegrees = readDouble(),
			longitudeDegrees = readDouble(),
			horizontalAccuracyMeters = readFloat(),
			altitudeMeters = readNullableDouble(),
			verticalAccuracyMeters = readNullableFloat(),
			speedMetersPerSecond = readNullableFloat(),
			bearingDegrees = readNullableFloat(),
			provider = readUTF(),
		)
		TYPE_ACTIVITY_TRANSITION -> ActivityTransitionPayload(readInt(), readInt(), readLong())
		TYPE_ACTIVITY_RECOGNITION -> ActivityRecognitionPayload(readInt(), readInt(), readNullableLong())
		TYPE_STEP_WINDOW -> readStepCounterWindowPayload(payloadVersion)
		TYPE_PRESSURE_WINDOW -> readPressureWindowPayload(payloadVersion)
		TYPE_WIFI_ATTEMPT -> WifiScanAttemptPayload(
			attemptId = readUTF(),
			outcome = WifiScanAttemptOutcome.entries[readInt()],
			resultCount = readNullableInt(),
			resultAgeMs = readNullableLong(),
		)
		TYPE_WIFI_RESULTS -> WifiResultSnapshotPayload(
			accessPoints = List(readBoundedCount()) {
				WifiAccessPointEvidence(
					identifierToken = readUTF(),
					frequencyMhz = readInt(),
					signalLevelDbm = readInt(),
					providerTimestampNanos = if (payloadVersion >= WIFI_ITEM_TIME_VERSION) {
						readNullableLong()
					} else null,
				)
			},
			platformTimestampMs = readNullableLong(),
			resultAgeMs = readNullableLong(),
		)
		TYPE_CELL_SNAPSHOT -> CellSnapshotPayload(
			subscriptionId = readNullableInt(),
			observations = List(readBoundedCount()) {
				CellObservationEvidence(
					identifierToken = readUTF(),
					radioType = readUTF(),
					registered = readBoolean(),
					signalLevelDbm = readNullableInt(),
					providerTimestampNanos = readNullableLong(),
				)
			},
			refreshOutcome = CellRefreshOutcome.entries[readInt()],
		)
		else -> error("Unsupported source payload type $type")
	}

	private fun DataInputStream.readBoundedCount(): Int = readInt().also { count ->
		require(count in 0..MAX_COLLECTION_SIZE) { "Invalid source payload collection size $count" }
	}

	private fun DataInputStream.readStepCounterWindowPayload(
		payloadVersion: Int,
	): StepCounterWindowPayload {
		val bootClockDomainId = readUTF()
		val firstCumulativeCount = readLong()
		val lastCumulativeCount = readLong()
		val deltaCount = readLong()
		val windowStartElapsedRealtimeNanos = readLong()
		val windowEndElapsedRealtimeNanos = readLong()
		val firstProviderSequence = readLong()
		val lastProviderSequence = readLong()
		val boundaryKind = if (payloadVersion >= STEP_BOUNDARY_KIND_PAYLOAD_VERSION) {
			stepBoundaryKindFromStableWireCode(readInt())
		} else {
			StepBoundaryKind.fromLegacyResetFlag(readBoolean())
		}
		return StepCounterWindowPayload(
			bootClockDomainId = bootClockDomainId,
			firstCumulativeCount = firstCumulativeCount,
			lastCumulativeCount = lastCumulativeCount,
			deltaCount = deltaCount,
			windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
			firstProviderSequence = firstProviderSequence,
			lastProviderSequence = lastProviderSequence,
			boundaryKind = boundaryKind,
		)
	}

	private fun DataInputStream.readPressureWindowPayload(payloadVersion: Int): PressureWindowPayload {
		val legacyShape = PressureWindowPayload(
			sampleCount = readInt(),
			meanHectopascals = readDouble(),
			sumSquaredDeviations = readDouble(),
			minimumHectopascals = readFloat(),
			maximumHectopascals = readFloat(),
			windowStartElapsedRealtimeNanos = readLong(),
			windowEndElapsedRealtimeNanos = readLong(),
			firstProviderSequence = readLong(),
			lastProviderSequence = readLong(),
		)
		if (payloadVersion < PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION) return legacyShape
		return legacyShape.copy(
			firstHectopascals = readFloat(),
			lastHectopascals = readFloat(),
			slopeHectopascalsPerSecond = readNullableDouble(),
			rSquared = readNullableDouble(),
			sensorAccuracy = pressureSensorAccuracyFromStableWireCode(readInt()),
			effectiveSamplePeriodMicros = readInt(),
			effectiveMaximumReportLatencyMicros = readInt(),
			targetWindowDurationNanos = readLong(),
			expectedSampleCount = readInt(),
			maximumInterSampleGapNanos = readLong(),
			closureKind = pressureWindowClosureFromStableWireCode(readInt()),
		).also { it.requireQualifiedPressureShape() }
	}

	private fun DataOutputStream.writeNullableLong(value: Long?) {
		writeBoolean(value != null)
		if (value != null) writeLong(value)
	}

	private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

	private fun DataOutputStream.writeNullableInt(value: Int?) {
		writeBoolean(value != null)
		if (value != null) writeInt(value)
	}

	private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

	private fun DataOutputStream.writeNullableFloat(value: Float?) {
		writeBoolean(value != null)
		if (value != null) writeFloat(value)
	}

	private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null

	private fun DataOutputStream.writeNullableDouble(value: Double?) {
		writeBoolean(value != null)
		if (value != null) writeDouble(value)
	}

	private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

	private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
		.digest(this)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }

	private companion object {
		const val MINIMUM_VERSION = 1
		const val CURRENT_VERSION = PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION
		const val LEGACY_V27_VERSION = 1
		const val WIFI_ITEM_TIME_VERSION = 2
		const val MAX_COLLECTION_SIZE = 100_000
		const val TYPE_LOCATION_FIX = 1
		const val TYPE_ACTIVITY_TRANSITION = 2
		const val TYPE_ACTIVITY_RECOGNITION = 3
		const val TYPE_STEP_WINDOW = 4
		const val TYPE_PRESSURE_WINDOW = 5
		const val TYPE_WIFI_ATTEMPT = 6
		const val TYPE_WIFI_RESULTS = 7
		const val TYPE_CELL_SNAPSHOT = 8
	}
}

internal const val STEP_BOUNDARY_KIND_PAYLOAD_VERSION = 3
internal const val PRESSURE_QUALIFIED_WINDOW_PAYLOAD_VERSION = 4

private const val PRESSURE_ACCURACY_UNKNOWN_WIRE_CODE = 1
private const val PRESSURE_ACCURACY_UNRELIABLE_WIRE_CODE = 2
private const val PRESSURE_ACCURACY_LOW_WIRE_CODE = 3
private const val PRESSURE_ACCURACY_MEDIUM_WIRE_CODE = 4
private const val PRESSURE_ACCURACY_HIGH_WIRE_CODE = 5
private const val PRESSURE_CLOSURE_TARGET_ELAPSED_WIRE_CODE = 1
private const val PRESSURE_CLOSURE_SOURCE_BOUNDARY_WIRE_CODE = 2

private fun StepBoundaryKind.stableWireCode(): Int = when (this) {
	StepBoundaryKind.BASELINE -> 1
	StepBoundaryKind.COVERED -> 2
	StepBoundaryKind.COUNTER_RESET -> 3
	StepBoundaryKind.LEGACY_AMBIGUOUS -> throw IllegalArgumentException(
		"Legacy-ambiguous Steps boundaries have no stable wire code",
	)
}

private fun stepBoundaryKindFromStableWireCode(code: Int): StepBoundaryKind = when (code) {
	1 -> StepBoundaryKind.BASELINE
	2 -> StepBoundaryKind.COVERED
	3 -> StepBoundaryKind.COUNTER_RESET
	else -> error("Unsupported Steps boundary kind $code")
}

private fun PressureSensorAccuracy.stableWireCode(): Int = when (this) {
	PressureSensorAccuracy.UNKNOWN -> PRESSURE_ACCURACY_UNKNOWN_WIRE_CODE
	PressureSensorAccuracy.UNRELIABLE -> PRESSURE_ACCURACY_UNRELIABLE_WIRE_CODE
	PressureSensorAccuracy.LOW -> PRESSURE_ACCURACY_LOW_WIRE_CODE
	PressureSensorAccuracy.MEDIUM -> PRESSURE_ACCURACY_MEDIUM_WIRE_CODE
	PressureSensorAccuracy.HIGH -> PRESSURE_ACCURACY_HIGH_WIRE_CODE
	PressureSensorAccuracy.LEGACY_UNAVAILABLE -> throw IllegalArgumentException(
		"Legacy-unavailable Pressure accuracy has no version 4 wire code",
	)
}

private fun pressureSensorAccuracyFromStableWireCode(code: Int): PressureSensorAccuracy = when (code) {
	PRESSURE_ACCURACY_UNKNOWN_WIRE_CODE -> PressureSensorAccuracy.UNKNOWN
	PRESSURE_ACCURACY_UNRELIABLE_WIRE_CODE -> PressureSensorAccuracy.UNRELIABLE
	PRESSURE_ACCURACY_LOW_WIRE_CODE -> PressureSensorAccuracy.LOW
	PRESSURE_ACCURACY_MEDIUM_WIRE_CODE -> PressureSensorAccuracy.MEDIUM
	PRESSURE_ACCURACY_HIGH_WIRE_CODE -> PressureSensorAccuracy.HIGH
	else -> error("Unsupported Pressure sensor accuracy $code")
}

private fun PressureWindowClosureKind.stableWireCode(): Int = when (this) {
	PressureWindowClosureKind.TARGET_ELAPSED -> PRESSURE_CLOSURE_TARGET_ELAPSED_WIRE_CODE
	PressureWindowClosureKind.SOURCE_BOUNDARY -> PRESSURE_CLOSURE_SOURCE_BOUNDARY_WIRE_CODE
	PressureWindowClosureKind.LEGACY_UNAVAILABLE -> throw IllegalArgumentException(
		"Legacy-unavailable Pressure closure has no version 4 wire code",
	)
}

private fun pressureWindowClosureFromStableWireCode(code: Int): PressureWindowClosureKind = when (code) {
	PRESSURE_CLOSURE_TARGET_ELAPSED_WIRE_CODE -> PressureWindowClosureKind.TARGET_ELAPSED
	PRESSURE_CLOSURE_SOURCE_BOUNDARY_WIRE_CODE -> PressureWindowClosureKind.SOURCE_BOUNDARY
	else -> error("Unsupported Pressure window closure $code")
}

private fun PressureWindowPayload.requireLegacyPressureShape() {
	require(firstHectopascals == null)
	require(lastHectopascals == null)
	require(slopeHectopascalsPerSecond == null)
	require(rSquared == null)
	require(sensorAccuracy == PressureSensorAccuracy.LEGACY_UNAVAILABLE)
	require(effectiveSamplePeriodMicros == null)
	require(effectiveMaximumReportLatencyMicros == null)
	require(targetWindowDurationNanos == null)
	require(expectedSampleCount == null)
	require(maximumInterSampleGapNanos == null)
	require(closureKind == PressureWindowClosureKind.LEGACY_UNAVAILABLE) {
		"Qualified Pressure evidence cannot be encoded with a legacy payload version"
	}
}

private fun PressureWindowPayload.requireQualifiedPressureShape() {
	requireQualifiedPressureStatistics()
	requireQualifiedPressureTimeline()
	requireQualifiedPressureConfiguration()
	requireQualifiedPressureSampleShape()
}

private fun PressureWindowPayload.requireQualifiedPressureStatistics() {
	require(sampleCount > 0)
	require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
	require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
	require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
	require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
	require(meanHectopascals in minimumHectopascals.toDouble()..maximumHectopascals.toDouble())
	val first = requireNotNull(firstHectopascals)
	val last = requireNotNull(lastHectopascals)
	require(first.isFinite() && first in minimumHectopascals..maximumHectopascals)
	require(last.isFinite() && last in minimumHectopascals..maximumHectopascals)
}

private fun PressureWindowPayload.requireQualifiedPressureTimeline() {
	require(windowStartElapsedRealtimeNanos >= 0L)
	require(windowEndElapsedRealtimeNanos >= windowStartElapsedRealtimeNanos)
	require(firstProviderSequence > 0L)
	require(lastProviderSequence >= firstProviderSequence)
	// Subtracting positive ordered values avoids the overflow risk of computing last - first + 1.
	val providerSequenceSpan = lastProviderSequence - firstProviderSequence
	val sampleSpan = sampleCount.toLong() - 1L
	require(providerSequenceSpan == sampleSpan)
}

private fun PressureWindowPayload.requireQualifiedPressureConfiguration() {
	val samplePeriod = requireNotNull(effectiveSamplePeriodMicros)
	val reportLatency = requireNotNull(effectiveMaximumReportLatencyMicros)
	val targetDuration = requireNotNull(targetWindowDurationNanos)
	val expectedCount = requireNotNull(expectedSampleCount)
	require(samplePeriod > 0)
	require(reportLatency >= 0)
	require(targetDuration > 0L)
	require(expectedCount == expectedPressureSampleCount(targetDuration, samplePeriod))
	require(sensorAccuracy != PressureSensorAccuracy.LEGACY_UNAVAILABLE)
	require(closureKind != PressureWindowClosureKind.LEGACY_UNAVAILABLE)
}

private fun PressureWindowPayload.requireQualifiedPressureSampleShape() {
	val maximumGap = requireNotNull(maximumInterSampleGapNanos)
	require(maximumGap >= 0L)
	val observedSpan = windowEndElapsedRealtimeNanos - windowStartElapsedRealtimeNanos
	if (sampleCount == 1) {
		val first = requireNotNull(firstHectopascals)
		val last = requireNotNull(lastHectopascals)
		require(first == last)
		require(first == minimumHectopascals)
		require(last == maximumHectopascals)
		require(meanHectopascals == first.toDouble())
		require(sumSquaredDeviations == 0.0)
		require(observedSpan == 0L && maximumGap == 0L)
		require(slopeHectopascalsPerSecond == null && rSquared == null)
	} else {
		require(observedSpan > 0L && maximumGap in 1L..observedSpan)
		requireNotNull(slopeHectopascalsPerSecond).also { require(it.isFinite()) }
		if (sumSquaredDeviations > 0.0) {
			requireNotNull(rSquared).also { require(it.isFinite() && it in 0.0..1.0) }
		} else {
			require(rSquared == null)
		}
	}
}
