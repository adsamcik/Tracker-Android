package com.adsamcik.tracker.tracker.pipeline

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.signal.ActivitySignal
import com.adsamcik.tracker.stats.api.signal.CellSignal
import com.adsamcik.tracker.stats.api.signal.CellTowerReading
import com.adsamcik.tracker.stats.api.signal.LocationSignal
import com.adsamcik.tracker.stats.api.signal.LocationObservationSignal
import com.adsamcik.tracker.stats.api.signal.LocationDecisionSignal
import com.adsamcik.tracker.stats.api.signal.ObservationStamp
import com.adsamcik.tracker.stats.api.signal.PolicySignal
import com.adsamcik.tracker.stats.api.signal.PressureSignal
import com.adsamcik.tracker.stats.api.signal.StepSignal
import com.adsamcik.tracker.stats.api.signal.TrackingSignal
import com.adsamcik.tracker.stats.api.signal.WifiNetworkReading
import com.adsamcik.tracker.stats.api.signal.WifiSignal
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping
import com.adsamcik.tracker.stats.api.value.ActivityConfidence
import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import com.adsamcik.tracker.stats.api.value.SpeedMps
import com.adsamcik.tracker.stats.api.value.StepCount

/**
 * Converts raw tracker data into a unified [TrackingSignal].
 * Single creation point for all signal data, ensuring consistent value class wrapping.
 */
object SignalAdapter {

	/**
	 * Build a TrackingSignal from raw tracker sensor data.
	 *
	 * @param timestampMs Collection cycle timestamp
	 * @param elapsedRealtimeNanos Monotonic clock for sensor correlation
	 * @param latitude Raw latitude in degrees, null if no GPS fix
	 * @param longitude Raw longitude in degrees, null if no GPS fix
	 * @param accuracy Horizontal accuracy in meters
	 * @param speed Speed in m/s, null if unavailable
	 * @param altitude Datum-aware processed altitude in meters, null if unavailable
	 * @param rawGpsAltitude Raw WGS-84 ellipsoid GPS altitude, null if unavailable
	 * @param verticalAccuracy Vertical accuracy in meters, null if unavailable
	 * @param speedAccuracy Speed accuracy in m/s, null if unavailable
	 * @param distanceDelta Distance from previous location in meters
	 * @param provider Location provider name
	 * @param activityTypeCode Google Play Services activity int code
	 * @param activityConfidence Activity confidence 0-100
	 * @param stepDelta Steps since last signal
	 * @param totalStepsSinceBoot Total steps since device boot
	 * @param stepSensorValueStart Raw sensor value at interval start
	 * @param stepSensorValueEnd Raw sensor value at interval end
	 * @param stepSensorReset Whether step sensor reset was detected
	 * @param cellTowers Cell tower readings, null if unavailable
	 * @param wifiNetworks WiFi network readings, null if unavailable
	 * @param pressureHpa Barometric pressure in hPa, null if unavailable
	 * @param pressureAltitudeM Barometric altitude in meters, null if unavailable
	 * @param policyTier Current tracking policy tier, null if unavailable
	 * @param policyName Current tracking policy name, null if unavailable
	 * @param persistenceSignalId Stable outer-WAL identity supplied by the producer, null when the
	 * buffer should create one for this staging attempt
	 */
	fun buildSignal(
		timestampMs: Long,
		elapsedRealtimeNanos: Long = 0L,
		locationObservation: LocationObservationSignal? = null,
		latitude: Double? = null,
		longitude: Double? = null,
		accuracy: Float? = null,
		speed: Float? = null,
		rawPlatformSpeed: Float? = null,
		rawPlatformSpeedAccuracy: Float? = null,
		bearingDeg: Float? = null,
		bearingAccuracyDeg: Float? = null,
		altitude: Float? = null,
		rawGpsAltitude: Float? = null,
		altitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
		altitudeSource: AltitudeSource = AltitudeSource.UNKNOWN_LEGACY,
		altitudeConversionStatus: AltitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
		rawGpsAltitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
		altitudeModelVersion: Int = 0,
		altitudeEstimatorVersion: Int = 0,
		altitudeCalibrationVersion: Int = 0,
		verticalAccuracy: Float? = null,
		speedAccuracy: Float? = null,
		distanceDelta: Float? = null,
		provider: String = "fused",
		receivedElapsedRealtimeNanos: Long = 0L,
		acquisitionMode: String = "UNKNOWN",
		requestPriority: String = "UNKNOWN",
		permissionPrecision: String = "UNKNOWN",
		batchIndex: Int = 0,
		batchSize: Int = 1,
		isMock: Boolean = false,
		sourceEventId: String? = null,
		clockDomainId: String? = null,
		bootClockDomainId: String? = null,
		locationDecision: LocationDecisionSignal? = null,
		activityTypeCode: Int? = null,
		activityConfidence: Int? = null,
		activityFresh: Boolean = true,
		activitySourceElapsedRealtimeNanos: Long? = null,
		activitySourceSequence: Long? = null,
		stepDelta: Int? = null,
		totalStepsSinceBoot: Long? = null,
		stepSensorValueStart: Int = 0,
		stepSensorValueEnd: Int = 0,
		stepSensorReset: Boolean = false,
		stepWindowStartElapsedRealtimeNanos: Long? = null,
		stepWindowEndElapsedRealtimeNanos: Long? = null,
		stepSourceFirstSequence: Long? = null,
		stepSourceLastSequence: Long? = null,
		cellTowers: List<CellTowerReading>? = null,
		cellObservedAtMs: Long? = null,
		cellObservedElapsedRealtimeNanos: Long? = null,
		cellSourceSequence: Long? = null,
		wifiNetworks: List<WifiNetworkReading>? = null,
		pressureHpa: Float? = null,
		wifiTimestampMs: Long? = null,
		wifiElapsedRealtimeNanos: Long? = null,
		wifiSourceSequence: Long? = null,
		wifiLatitude: Double? = null,
		wifiLongitude: Double? = null,
		wifiCoordinateProvenance: com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance = com.adsamcik.tracker.stats.api.signal.ObservationCoordinateProvenance.UNKNOWN,
		pressureAltitudeM: Float? = null,
		pressureSampleCount: Int = 1,
		pressureMinHpa: Float? = null,
		pressureMaxHpa: Float? = null,
		pressureStandardDeviationHpa: Float = 0f,
		pressureWindowStartElapsedRealtimeNanos: Long? = null,
		pressureWindowEndElapsedRealtimeNanos: Long? = null,
		pressureSourceFirstSequence: Long? = null,
		pressureSourceLastSequence: Long? = null,
		policyTier: PolicyTier? = null,
		policyName: String? = null,
		persistenceSignalId: String? = null,
	): TrackingSignal {
		val locationSignal = if (latitude != null && longitude != null && accuracy != null) {
			LocationSignal(
				coordinate = CoordinateE7(
					lat = LatE7.fromDegrees(latitude),
					lon = LonE7.fromDegrees(longitude),
				),
				horizontalAccuracyM = accuracy,
				speed = speed?.let { SpeedMps.coerced(it) },
				rawPlatformSpeedMps = rawPlatformSpeed,
				rawPlatformSpeedAccuracyMps = rawPlatformSpeedAccuracy,
				bearingDeg = bearingDeg,
				bearingAccuracyDeg = bearingAccuracyDeg,
				altitudeM = altitude,
				rawGpsAltitudeM = rawGpsAltitude,
				altitudeDatum = altitudeDatum,
				altitudeSource = altitudeSource,
				altitudeConversionStatus = altitudeConversionStatus,
				rawGpsAltitudeDatum = rawGpsAltitudeDatum,
				altitudeModelVersion = altitudeModelVersion,
				altitudeEstimatorVersion = altitudeEstimatorVersion,
				altitudeCalibrationVersion = altitudeCalibrationVersion,
				verticalAccuracyM = verticalAccuracy,
				speedAccuracyMps = speedAccuracy,
				distanceDelta = distanceDelta?.let { DistanceM.coerced(it) },
				provider = provider,
				receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
				acquisitionMode = acquisitionMode,
				requestPriority = requestPriority,
				permissionPrecision = permissionPrecision,
				batchIndex = batchIndex,
				batchSize = batchSize,
				isMock = isMock,
				sourceEventId = sourceEventId,
			)
		} else {
			null
		}

		val activitySignal = if (activityTypeCode != null && activityConfidence != null) {
			ActivitySignal(
				type = ActivityTypeMapping.fromPlayServicesCode(activityTypeCode),
				confidence = ActivityConfidence.coerced(activityConfidence),
				stamp = observationStamp(
					cycleEpochMs = timestampMs,
					cycleElapsedRealtimeNanos = elapsedRealtimeNanos,
					sourceElapsedRealtimeNanos = activitySourceElapsedRealtimeNanos,
					sourceSequence = activitySourceSequence,
					clockDomainId = clockDomainId,
					bootClockDomainId = bootClockDomainId,
				),
			)
		} else {
			null
		}

		val stepSignal = if (stepDelta != null && stepDelta > 0) {
			StepSignal(
				stepDelta = StepCount.coerced(stepDelta),
				totalStepsSinceBoot = totalStepsSinceBoot ?: 0L,
				sensorValueStart = stepSensorValueStart,
				sensorValueEnd = stepSensorValueEnd,
				sensorReset = stepSensorReset,
				stamp = observationStamp(
					cycleEpochMs = timestampMs,
					cycleElapsedRealtimeNanos = elapsedRealtimeNanos,
					sourceElapsedRealtimeNanos = stepWindowEndElapsedRealtimeNanos,
					sourceFirstElapsedRealtimeNanos =
						stepWindowStartElapsedRealtimeNanos,
					sourceSequence = stepSourceLastSequence,
					sourceFirstSequence = stepSourceFirstSequence,
					clockDomainId = clockDomainId,
					bootClockDomainId = bootClockDomainId,
				),
			)
		} else {
			null
		}

		val cellSignal = if (!cellTowers.isNullOrEmpty()) {
			CellSignal(
				towers = cellTowers,
				stamp = observationStamp(
					cycleEpochMs = timestampMs,
					cycleElapsedRealtimeNanos = elapsedRealtimeNanos,
					sourceEpochMs = cellObservedAtMs,
					sourceElapsedRealtimeNanos = cellObservedElapsedRealtimeNanos,
					sourceSequence = cellSourceSequence,
					clockDomainId = clockDomainId,
					bootClockDomainId = bootClockDomainId,
				),
			)
		} else {
			null
		}

		val wifiCoordinate = if (wifiLatitude != null && wifiLongitude != null) {
			CoordinateE7(
				lat = LatE7.fromDegrees(wifiLatitude),
				lon = LonE7.fromDegrees(wifiLongitude),
			)
		} else {
			null
		}
		val wifiSignal = if (!wifiNetworks.isNullOrEmpty()) {
			WifiSignal(
				networks = wifiNetworks,
				timestampMs = wifiTimestampMs?.let(::EpochMs),
				coordinate = wifiCoordinate,
				coordinateProvenance = wifiCoordinateProvenance,
				stamp = observationStamp(
					cycleEpochMs = timestampMs,
					cycleElapsedRealtimeNanos = elapsedRealtimeNanos,
					sourceEpochMs = wifiTimestampMs,
					sourceElapsedRealtimeNanos = wifiElapsedRealtimeNanos,
					sourceSequence = wifiSourceSequence,
					clockDomainId = clockDomainId,
					bootClockDomainId = bootClockDomainId,
				),
			)
		} else {
			null
		}

		val pressureSignal = if (pressureHpa != null && pressureAltitudeM != null) {
			PressureSignal(
				pressureHpa = pressureHpa,
				altitudeM = pressureAltitudeM,
				sampleCount = pressureSampleCount,
				minPressureHpa = pressureMinHpa ?: pressureHpa,
				maxPressureHpa = pressureMaxHpa ?: pressureHpa,
				standardDeviationHpa = pressureStandardDeviationHpa,
				windowStartElapsedRealtimeNanos = pressureWindowStartElapsedRealtimeNanos,
				windowEndElapsedRealtimeNanos = pressureWindowEndElapsedRealtimeNanos,
				stamp = observationStamp(
					cycleEpochMs = timestampMs,
					cycleElapsedRealtimeNanos = elapsedRealtimeNanos,
					sourceElapsedRealtimeNanos = pressureWindowEndElapsedRealtimeNanos,
					sourceFirstElapsedRealtimeNanos =
						pressureWindowStartElapsedRealtimeNanos,
					sourceSequence = pressureSourceLastSequence,
					sourceFirstSequence = pressureSourceFirstSequence,
					clockDomainId = clockDomainId,
					bootClockDomainId = bootClockDomainId,
				),
			)
		} else {
			null
		}

		val policySignal = if (policyTier != null) {
			PolicySignal(tier = policyTier, policyName = policyName)
		} else {
			null
		}

		return TrackingSignal(
			timestampMs = EpochMs(timestampMs),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			clockDomainId = clockDomainId,
			bootClockDomainId = bootClockDomainId,
			locationObservation = locationObservation,
			location = locationSignal,
			locationDecision = locationDecision,
			activity = activitySignal,
			activityFresh = activityFresh,
			steps = stepSignal,
			cells = cellSignal,
			wifi = wifiSignal,
			pressure = pressureSignal,
			policy = policySignal,
			persistenceSignalId = persistenceSignalId,
		)
	}

	private fun observationStamp(
		cycleEpochMs: Long,
		cycleElapsedRealtimeNanos: Long,
		sourceEpochMs: Long? = null,
		sourceElapsedRealtimeNanos: Long? = null,
		sourceFirstElapsedRealtimeNanos: Long? = null,
		sourceSequence: Long? = null,
		sourceFirstSequence: Long? = null,
		clockDomainId: String?,
		bootClockDomainId: String?,
	): ObservationStamp {
		val ageMs = if (
			cycleElapsedRealtimeNanos > 0L &&
			sourceElapsedRealtimeNanos != null &&
			sourceElapsedRealtimeNanos > 0L
		) {
			((cycleElapsedRealtimeNanos - sourceElapsedRealtimeNanos).coerceAtLeast(0L) /
				1_000_000L)
		} else {
			null
		}
		return ObservationStamp(
			sourceEpochMs = sourceEpochMs,
			sourceElapsedRealtimeNanos = sourceElapsedRealtimeNanos,
			sourceFirstElapsedRealtimeNanos = sourceFirstElapsedRealtimeNanos,
			receivedEpochMs = cycleEpochMs,
			receivedElapsedRealtimeNanos = cycleElapsedRealtimeNanos,
			sourceSequence = sourceSequence,
			sourceFirstSequence = sourceFirstSequence,
			clockDomainId = clockDomainId,
			bootClockDomainId = bootClockDomainId,
			sourceAgeMs = ageMs,
			timeUncertaintyMs = ageMs,
		)
	}
}
