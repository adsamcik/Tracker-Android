package com.adsamcik.tracker.tracker.source.ingress

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellObservationEvidence
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StepBoundaryKind
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptOutcome
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptPayload
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
			input.readPayload(payloadVersion)
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
		TYPE_PRESSURE_WINDOW -> PressureWindowPayload(
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
		const val CURRENT_VERSION = STEP_BOUNDARY_KIND_PAYLOAD_VERSION
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
