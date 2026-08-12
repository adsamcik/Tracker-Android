package com.adsamcik.tracker.tracker.source.projection

import android.location.Location
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationFixMetadata
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.tracker.altitude.BarometricAltitudeFormula
import com.adsamcik.tracker.tracker.data.collection.NormalizedCellScanData
import com.adsamcik.tracker.tracker.data.collection.NormalizedWifiScanData
import com.adsamcik.tracker.tracker.data.collection.PressureReading
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.AdmittedSourceEvent
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
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
class EventTrackingFrameProjection @Inject constructor(
	private val database: AppDatabase,
) : Projection {
	override val id: String = ID
	override val version: Int = 2

	override suspend fun apply(
		event: AdmittedSourceEvent<out SourcePayload>,
		context: ProjectionContext,
	) {
		val logicalTrackingId = event.evidence.logicalTrackingId?.value ?: return
		val sessionState = database.sourceSessionDao().session(logicalTrackingId)?.state
		if (sessionState != null && sessionState in TERMINAL_SESSION_STATES) return
		val cycle = event.toEventTrackingFrame() ?: return
		context.recordOutbox(
			ProjectionOutboxEffect(
				stableId = "$ID:v$version:${event.eventId.value}",
				kind = effectKind(logicalTrackingId),
				payloadVersion = EventTrackingFrameEffectCodec.VERSION,
				payload = EventTrackingFrameEffectCodec.encode(logicalTrackingId, cycle),
			),
		)
	}

	companion object {
		const val ID = "event-tracking-frame"
		const val OUTBOX_KIND_PREFIX = "event-tracking-frame-v2:"
		const val LEGACY_OUTBOX_KIND = "event-tracking-frame-v1"
		private val TERMINAL_SESSION_STATES = setOf("CLOSED", "FAILED")

		fun effectKind(logicalTrackingId: String): String = "$OUTBOX_KIND_PREFIX$logicalTrackingId"
	}
}

internal fun AdmittedSourceEvent<out SourcePayload>.toEventTrackingFrame(): TrackingCycle? {
	val timestampMs = evidence.wallTimeMs ?: evidence.acquiredAtMs
	val persistenceId = "source-event:${eventId.value}"
	return when (val payload = evidence.payload) {
		is LocationFixPayload -> {
			val location = Location(payload.provider).apply {
				latitude = payload.latitudeDegrees
				longitude = payload.longitudeDegrees
				accuracy = payload.horizontalAccuracyMeters
				time = timestampMs
				elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos
				payload.altitudeMeters?.let { altitude = it }
				payload.verticalAccuracyMeters?.let { verticalAccuracyMeters = it }
				payload.speedMetersPerSecond?.let { speed = it }
				payload.bearingDegrees?.let { bearing = it }
			}
			TrackingCycle(
				timestampMs = timestampMs,
				elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
				location = LocationData(
					locations = listOf(location),
					previousLocation = null,
					distance = null,
					fixMetadata = listOf(
						LocationFixMetadata(
							callbackId = evidence.providerDedupKey,
							sourceEventId = eventId.value,
							clockDomainId = evidence.clockDomainId,
							receivedAtMs = timestampMs,
							receivedElapsedRealtimeNanos = evidence.receivedElapsedRealtimeNanos,
						),
					),
				),
				rawGpsAltitude = payload.altitudeMeters,
				persistenceSignalId = persistenceId,
			)
		}
		is ActivityRecognitionPayload -> TrackingCycle(
			timestampMs = timestampMs,
			elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
			activity = ActivityInfo(
				activityType = payload.activityType.toPlayServicesActivityCode(),
				confidence = payload.confidencePercent,
			),
			activityFresh = true,
			activitySourceElapsedRealtimeNanos = payload.providerElapsedRealtimeNanos
				?: evidence.observedElapsedRealtimeNanos,
			activitySourceSequence = evidence.sourceSequence,
			persistenceSignalId = persistenceId,
		)
		is ActivityTransitionPayload -> TrackingCycle(
			timestampMs = timestampMs,
			elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
			activity = ActivityInfo(
				activityType = payload.activityType.toPlayServicesActivityCode(),
				confidence = 100,
			),
			activityFresh = true,
			activitySourceElapsedRealtimeNanos = payload.providerElapsedRealtimeNanos,
			activitySourceSequence = evidence.sourceSequence,
			persistenceSignalId = persistenceId,
		)
		is WifiResultSnapshotPayload -> {
			if (payload.accessPoints.isEmpty()) return null
			TrackingCycle(
				timestampMs = timestampMs,
				elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
				normalizedWifiScan = NormalizedWifiScanData(
					networks = payload.accessPoints.map { accessPoint ->
						WifiNetworkReading(
							bssid = accessPoint.identifierToken,
							ssid = "",
							capabilities = "",
							frequency = accessPoint.frequencyMhz,
							level = accessPoint.signalLevelDbm,
						)
					},
					observedAtMs = timestampMs,
					observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
					sourceSequence = evidence.sourceSequence,
				),
				persistenceSignalId = persistenceId,
			)
		}
		is CellSnapshotPayload -> {
			val towers = payload.observations.filter { it.registered }.map { observation ->
				CellTowerReading(
					cellId = observation.identifierToken.toStableLong(),
					mcc = "",
					mnc = "",
					networkType = observation.radioType.toLegacyNetworkType(),
					signalStrength = observation.signalLevelDbm ?: Int.MAX_VALUE,
				)
			}
			if (towers.isEmpty()) return null
			TrackingCycle(
				timestampMs = timestampMs,
				elapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
				normalizedCellScan = NormalizedCellScanData(
					towers = towers,
					observedAtMs = timestampMs,
					observedElapsedRealtimeNanos = evidence.observedElapsedRealtimeNanos,
					sourceSequence = evidence.sourceSequence,
				),
				persistenceSignalId = persistenceId,
			)
		}
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

private fun Int.toPlayServicesActivityCode(): Int = when (this) {
	StableActivityTypeCode.STILL -> 3
	StableActivityTypeCode.WALKING -> 7
	StableActivityTypeCode.RUNNING -> 8
	StableActivityTypeCode.ON_BICYCLE -> 1
	StableActivityTypeCode.IN_VEHICLE -> 0
	StableActivityTypeCode.ON_FOOT -> 2
	StableActivityTypeCode.TILTING -> 5
	else -> 4
}

private fun String.toLegacyNetworkType(): Int = when (uppercase()) {
	"GSM" -> 1
	"CDMA" -> 2
	"WCDMA", "TDSCDMA" -> 3
	"LTE" -> 4
	"NR" -> 5
	else -> 0
}

private fun String.toStableLong(): Long = runCatching {
	java.lang.Long.parseUnsignedLong(take(16).padStart(16, '0'), 16)
}.getOrElse {
	fold(1125899906842597L) { hash, char -> hash * 31L + char.code }
}

/** Stable, versioned encoding for the cross-transaction compatibility effect. */
internal object EventTrackingFrameEffectCodec {
	const val VERSION = 2
	private const val LEGACY_VERSION = 1
	private const val KIND_STEPS = 1
	private const val KIND_PRESSURE = 2
	private const val KIND_LOCATION = 3
	private const val KIND_ACTIVITY = 4
	private const val KIND_WIFI = 5
	private const val KIND_CELL = 6
	private const val MAX_COLLECTION_SIZE = 100_000

	fun encode(logicalTrackingId: String, cycle: TrackingCycle): ByteArray = ByteArrayOutputStream().use { bytes ->
		require(logicalTrackingId.isNotBlank())
		DataOutputStream(bytes).use { output ->
			output.writeInt(VERSION)
			output.writeUTF(logicalTrackingId)
			val kind = cycle.effectKind()
			output.writeInt(kind)
			output.writeLong(cycle.timestampMs)
			output.writeLong(cycle.elapsedRealtimeNanos)
			output.writeUTF(cycle.persistenceSignalId)
			when (kind) {
				KIND_STEPS -> {
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
				KIND_PRESSURE -> requireNotNull(cycle.pressure).let { pressure ->
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
				}
				KIND_LOCATION -> requireNotNull(cycle.location).let { locationData ->
					val location = locationData.lastLocation
					val metadata = locationData.lastFixMetadata
					output.writeUTF(location.provider.orEmpty())
					output.writeDouble(location.latitude)
					output.writeDouble(location.longitude)
					output.writeFloat(location.accuracy)
					output.writeNullableDouble(location.altitude.takeIf { location.hasAltitude() })
					output.writeNullableFloat(
						location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
					)
					output.writeNullableFloat(location.speed.takeIf { location.hasSpeed() })
					output.writeNullableFloat(location.bearing.takeIf { location.hasBearing() })
					output.writeNullableUtf(metadata.callbackId)
					output.writeNullableUtf(metadata.sourceEventId)
					output.writeNullableUtf(metadata.clockDomainId)
					output.writeLong(metadata.receivedAtMs)
					output.writeLong(metadata.receivedElapsedRealtimeNanos)
				}
				KIND_ACTIVITY -> requireNotNull(cycle.activity).let { activity ->
					output.writeInt(activity.activityType)
					output.writeInt(activity.confidence)
					output.writeLong(requireNotNull(cycle.activitySourceElapsedRealtimeNanos))
					output.writeLong(requireNotNull(cycle.activitySourceSequence))
				}
				KIND_WIFI -> requireNotNull(cycle.normalizedWifiScan).let { wifi ->
					output.writeLong(wifi.observedAtMs)
					output.writeLong(wifi.observedElapsedRealtimeNanos)
					output.writeLong(wifi.sourceSequence)
					output.writeInt(wifi.networks.size)
					wifi.networks.forEach { network ->
						output.writeUTF(network.bssid)
						output.writeUTF(network.ssid)
						output.writeUTF(network.capabilities)
						output.writeInt(network.frequency)
						output.writeInt(network.level)
					}
				}
				KIND_CELL -> requireNotNull(cycle.normalizedCellScan).let { cell ->
					output.writeLong(cell.observedAtMs)
					output.writeLong(cell.observedElapsedRealtimeNanos)
					output.writeLong(cell.sourceSequence)
					output.writeInt(cell.towers.size)
					cell.towers.forEach { tower ->
						output.writeLong(tower.cellId)
						output.writeUTF(tower.mcc)
						output.writeUTF(tower.mnc)
						output.writeInt(tower.networkType)
						output.writeInt(tower.signalStrength)
						output.writeInt(tower.areaCode)
					}
				}
			}
		}
		bytes.toByteArray()
	}

	fun decode(payload: ByteArray, payloadVersion: Int): EventTrackingFrameDelivery {
		require(payloadVersion == LEGACY_VERSION || payloadVersion == VERSION) {
			"Unsupported event-frame effect version $payloadVersion"
		}
		return DataInputStream(ByteArrayInputStream(payload)).use { input ->
			require(input.readInt() == payloadVersion)
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
				KIND_LOCATION -> {
					require(payloadVersion >= VERSION)
					val provider = input.readUTF()
					val location = Location(provider).apply {
						latitude = input.readDouble()
						longitude = input.readDouble()
						accuracy = input.readFloat()
						time = timestamp
						elapsedRealtimeNanos = elapsed
						input.readNullableDouble()?.let { altitude = it }
						input.readNullableFloat()?.let { verticalAccuracyMeters = it }
						input.readNullableFloat()?.let { speed = it }
						input.readNullableFloat()?.let { bearing = it }
					}
					val metadata = LocationFixMetadata(
						callbackId = input.readNullableUtf(),
						sourceEventId = input.readNullableUtf(),
						clockDomainId = input.readNullableUtf(),
						receivedAtMs = input.readLong(),
						receivedElapsedRealtimeNanos = input.readLong(),
					)
					TrackingCycle(
						timestampMs = timestamp,
						elapsedRealtimeNanos = elapsed,
						location = LocationData(listOf(location), null, null, listOf(metadata)),
						rawGpsAltitude = location.altitude.takeIf { location.hasAltitude() },
						persistenceSignalId = persistenceId,
					)
				}
				KIND_ACTIVITY -> {
					require(payloadVersion >= VERSION)
					TrackingCycle(
						timestampMs = timestamp,
						elapsedRealtimeNanos = elapsed,
						activity = ActivityInfo(input.readInt(), input.readInt()),
						activityFresh = true,
						activitySourceElapsedRealtimeNanos = input.readLong(),
						activitySourceSequence = input.readLong(),
						persistenceSignalId = persistenceId,
					)
				}
				KIND_WIFI -> {
					require(payloadVersion >= VERSION)
					val observedAt = input.readLong()
					val observedElapsed = input.readLong()
					val sourceSequence = input.readLong()
					val networks = List(input.readBoundedCount()) {
						WifiNetworkReading(
							bssid = input.readUTF(),
							ssid = input.readUTF(),
							capabilities = input.readUTF(),
							frequency = input.readInt(),
							level = input.readInt(),
						)
					}
					TrackingCycle(
						timestampMs = timestamp,
						elapsedRealtimeNanos = elapsed,
						normalizedWifiScan = NormalizedWifiScanData(
							networks,
							observedAt,
							observedElapsed,
							sourceSequence,
						),
						persistenceSignalId = persistenceId,
					)
				}
				KIND_CELL -> {
					require(payloadVersion >= VERSION)
					val observedAt = input.readLong()
					val observedElapsed = input.readLong()
					val sourceSequence = input.readLong()
					val towers = List(input.readBoundedCount()) {
						CellTowerReading(
							cellId = input.readLong(),
							mcc = input.readUTF(),
							mnc = input.readUTF(),
							networkType = input.readInt(),
							signalStrength = input.readInt(),
							areaCode = input.readInt(),
						)
					}
					TrackingCycle(
						timestampMs = timestamp,
						elapsedRealtimeNanos = elapsed,
						normalizedCellScan = NormalizedCellScanData(
							towers,
							observedAt,
							observedElapsed,
							sourceSequence,
						),
						persistenceSignalId = persistenceId,
					)
				}
			else -> error("Unknown event-frame effect kind $kind")
			}
			require(input.available() == 0) { "Trailing event-frame effect bytes" }
			EventTrackingFrameDelivery(logicalTrackingId, cycle)
		}
	}

	private fun TrackingCycle.effectKind(): Int = when {
		location != null -> KIND_LOCATION
		activityFresh && activity != null -> KIND_ACTIVITY
		normalizedWifiScan != null -> KIND_WIFI
		normalizedCellScan != null -> KIND_CELL
		pressure != null -> KIND_PRESSURE
		stepDelta != null -> KIND_STEPS
		else -> error("Tracking cycle has no event-owned payload")
	}

	private fun DataInputStream.readBoundedCount(): Int = readInt().also { count ->
		require(count in 0..MAX_COLLECTION_SIZE) { "Invalid event-frame collection size $count" }
	}
}

private fun DataOutputStream.writeNullableUtf(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullableUtf(): String? = if (readBoolean()) readUTF() else null

private fun DataOutputStream.writeNullableDouble(value: Double?) {
	writeBoolean(value != null)
	if (value != null) writeDouble(value)
}

private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

private fun DataOutputStream.writeNullableFloat(value: Float?) {
	writeBoolean(value != null)
	if (value != null) writeFloat(value)
}

private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null

internal data class EventTrackingFrameDelivery(
	val logicalTrackingId: String,
	val cycle: TrackingCycle,
)
