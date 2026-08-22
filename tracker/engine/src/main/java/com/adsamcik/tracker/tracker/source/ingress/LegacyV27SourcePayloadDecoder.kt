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
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptOutcome
import com.adsamcik.tracker.tracker.source.model.WifiScanAttemptPayload
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Frozen decoder for payload version 1 as shipped with database v27. */
internal object LegacyV27SourcePayloadDecoder {
	fun decode(source: SourceKind, bytes: ByteArray): SourcePayload {
		val payload = DataInputStream(ByteArrayInputStream(bytes)).use { input -> input.readPayload() }
		require(payload.source == source) { "Encoded payload source does not match WAL source" }
		return payload
	}

	private fun DataInputStream.readPayload(): SourcePayload = when (val type = readInt()) {
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
		TYPE_STEP_WINDOW -> StepCounterWindowPayload(
			bootClockDomainId = readUTF(),
			firstCumulativeCount = readLong(),
			lastCumulativeCount = readLong(),
			deltaCount = readLong(),
			windowStartElapsedRealtimeNanos = readLong(),
			windowEndElapsedRealtimeNanos = readLong(),
			firstProviderSequence = readLong(),
			lastProviderSequence = readLong(),
			baselineReset = readBoolean(),
		)
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

	private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

	private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

	private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null

	private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

	private const val MAX_COLLECTION_SIZE = 100_000
	private const val TYPE_LOCATION_FIX = 1
	private const val TYPE_ACTIVITY_TRANSITION = 2
	private const val TYPE_ACTIVITY_RECOGNITION = 3
	private const val TYPE_STEP_WINDOW = 4
	private const val TYPE_PRESSURE_WINDOW = 5
	private const val TYPE_WIFI_ATTEMPT = 6
	private const val TYPE_WIFI_RESULTS = 7
	private const val TYPE_CELL_SNAPSHOT = 8
}
