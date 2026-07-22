package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.shared.base.Time
import android.os.Build
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.data.ProcessedAltitudeData
import com.adsamcik.tracker.shared.model.AltitudeConversionStatus
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessor
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.cancel
import kotlin.math.abs

internal class LocationTrackerComponent(
	private val trackingParamsRepository: TrackingParamsRepository? = null,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val altitudeProcessorFactory: () -> AltitudeProcessor = { AltitudeProcessor() },
) : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(
			TrackerComponentRequirement.LOCATION
	)

	private var altitudeProcessor: AltitudeProcessor? = null
	private var context: Context? = null
	private var lastAcceptedLocation: Location? = null
	private var lastSmoothedSpeed: Float = 0f
	private var settingsScope: CoroutineScope? = null
	private var settingsJob: Job? = null
	@Volatile private var requiredAccuracyMeters = TrackingParamsState.DEFAULT_REQUIRED_ACCURACY

	/**
	 * Raw GPS altitude (before fusion) from the most recent location update.
	 * Null if the location had no altitude or the component is not enabled.
	 */
	var lastRawGpsAltitudeM: Double? = null
		private set

	override suspend fun onEnable(context: Context) {
		this.context = context.applicationContext
		trackingParamsRepository?.let { repository ->
			requiredAccuracyMeters = repository.data.first().requiredAccuracyMeters
			settingsScope = CoroutineScope(SupervisorJob() + dispatchers.default)
			settingsJob = repository.data
				.onEach { requiredAccuracyMeters = it.requiredAccuracyMeters }
				.launchIn(requireNotNull(settingsScope))
		}
		altitudeProcessor = altitudeProcessorFactory()
		lastAcceptedLocation = null
		lastSmoothedSpeed = 0f
	}

	override suspend fun onDisable(context: Context) {
		altitudeProcessor?.reset()
		altitudeProcessor = null
		settingsJob?.cancel()
		settingsJob = null
		settingsScope?.cancel()
		settingsScope = null
		this.context = null
		lastRawGpsAltitudeM = null
		lastAcceptedLocation = null
		lastSmoothedSpeed = 0f
	}


	private fun calculateSpeed(prevLocation: Location, location: Location): Float {
		val recordedSpeed = location.speed
		val distance = location.distanceTo(prevLocation)
		val deltaS = elapsedSecondsBetween(prevLocation, location) ?: return recordedSpeed
		val calculatedSpeed = (distance / deltaS).toFloat()

		val rawSpeed = if (recordedSpeed <= 0f) {
			calculatedSpeed
		} else {
			val changePercentage = abs(calculatedSpeed - recordedSpeed) / recordedSpeed
			if (changePercentage >= MAX_ALLOWED_DIFFERENCE_TO_COMPUTED) {
				calculatedSpeed
			} else {
				recordedSpeed
			}
		}

		// EMA smoothing: dampen GPS jitter spikes while following real speed changes.
		// Alpha near 0.4 smooths single-sample outliers without excessive lag.
		val smoothed = if (lastSmoothedSpeed <= 0f) {
			rawSpeed
		} else {
			SPEED_SMOOTHING_ALPHA * rawSpeed + (1f - SPEED_SMOOTHING_ALPHA) * lastSmoothedSpeed
		}
		lastSmoothedSpeed = smoothed
		return smoothed
	}

	private fun isTeleportJump(previousLocation: Location, location: Location): Boolean {
		val distance = location.distanceTo(previousLocation)

		// Absolute distance guard: any single jump exceeding MAX_JUMP_DISTANCE_METERS
		// is treated as a GPS teleport regardless of elapsed time. This catches stale
		// cached locations from prior sessions where the large time delta makes the
		// speed-based check pass (e.g., 9,000 km over 24 h ≈ 375 km/h < 500 km/h).
		if (distance > MAX_JUMP_DISTANCE_METERS) return true

		val deltaS = elapsedSecondsBetween(previousLocation, location)
		if (deltaS == null || deltaS <= 0.0) {
			// Cannot determine elapsed time — fall back to distance-only check.
			// A conservative threshold avoids silently accepting large jumps.
			return distance > UNKNOWN_TIME_JUMP_DISTANCE_METERS
		}

		val calculatedSpeed = (distance / deltaS).toFloat()
		return calculatedSpeed > MAX_ABSOLUTE_SPEED_METERS_PER_SECOND
	}

	private fun elapsedSecondsBetween(previousLocation: Location, location: Location): Double? {
		val elapsedRealtimeDelta = location.elapsedRealtimeNanos - previousLocation.elapsedRealtimeNanos
		if (location.elapsedRealtimeNanos > 0L &&
			previousLocation.elapsedRealtimeNanos > 0L &&
			elapsedRealtimeDelta > 0L
		) {
			return elapsedRealtimeDelta.toDouble() / Time.SECOND_IN_NANOSECONDS.toDouble()
		}

		val wallClockDelta = location.time - previousLocation.time
		if (wallClockDelta > 0L) {
			return wallClockDelta.toDouble() / Time.SECOND_IN_MILLISECONDS.toDouble()
		}

		return null
	}

	private fun isLocationUsable(location: Location): Boolean {
		val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			location.isMock
		} else {
			@Suppress("DEPRECATION")
			location.isFromMockProvider
		}
		if (isMock || !location.hasAccuracy()) return false
		if (location.accuracy > requiredAccuracyMeters) return false

		val previous = lastAcceptedLocation ?: return true
		val currentElapsed = location.elapsedRealtimeNanos
		val previousElapsed = previous.elapsedRealtimeNanos
		if (currentElapsed > 0L && previousElapsed > 0L) {
			if (currentElapsed <= previousElapsed) return false
		} else if (location.time <= previous.time) {
			return false
		}

		// Providers can repeat their last fix. Persisting it adds I/O and storage without adding
		// information, and can distort downstream duration/sample-rate calculations.
		if (location.latitude != previous.latitude ||
			location.longitude != previous.longitude ||
			location.accuracy != previous.accuracy ||
			location.speed != previous.speed ||
			location.bearing != previous.bearing ||
			location.hasAltitude() != previous.hasAltitude() ||
			(location.hasAltitude() && location.altitude != previous.altitude)
		) return true

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if (location.hasVerticalAccuracy() != previous.hasVerticalAccuracy() ||
				(location.hasVerticalAccuracy() &&
					location.verticalAccuracyMeters != previous.verticalAccuracyMeters) ||
				location.hasSpeedAccuracy() != previous.hasSpeedAccuracy() ||
				(location.hasSpeedAccuracy() &&
					location.speedAccuracyMetersPerSecond != previous.speedAccuracyMetersPerSecond)
			) return true
		}

		return false
	}

	override suspend fun onDataUpdated(
			cycle: TrackingCycle,
			collectionData: MutableCollectionData
	) {
		val locationResult = requireNotNull(cycle.location)

		val location = locationResult.lastLocation
		val previousLocation = lastAcceptedLocation ?: locationResult.previousLocation
		if (!isLocationUsable(location)) return

		if (previousLocation != null && isTeleportJump(previousLocation, location)) {
			// Rebase to the teleported location so tracking can recover.
			lastAcceptedLocation = Location(location)
			lastRawGpsAltitudeM = null
			return
		}

		previousLocation?.let {
			val correctedSpeed = calculateSpeed(previousLocation, location)
			location.speed = correctedSpeed
		}

		// Distance from the previously *accepted* location. Measured here — not taken from the
		// trigger's raw per-fix distance — so segments are bridged across cycles dropped by the
		// pre-tracker accuracy gate or teleport guard, preventing systematic distance under-reporting.
		val distanceFromPrevious = previousLocation?.let { location.distanceTo(it) } ?: 0f

		// Capture raw GPS altitude before processing. android.location.Location.altitude is defined
		// against WGS-84 ellipsoid and remains untouched for the rest of this method.
		lastRawGpsAltitudeM = location.altitude.takeIf { location.hasAltitude() && it.isFinite() }

		// Apply altitude processing pipeline (geoid correction + accuracy gating + Kalman fusion).
		// The processed estimate travels separately so neither MSL nor fusion output overwrites the
		// provider Location's raw ellipsoid altitude.
		val ctx = context
		val processor = altitudeProcessor
		val processed = if (ctx != null && processor != null) {
			processor.processWithBarometerResult(ctx, location, cycle.pressure?.pressureHpa)
		} else {
			null
		}
		collectionData.rawGpsAltitudeM = processed?.rawWgs84EllipsoidAltitudeM?.toFloat()
			?: lastRawGpsAltitudeM?.toFloat()
		collectionData.processedAltitude = if (processed != null) {
			ProcessedAltitudeData(
				altitudeM = processed.altitudeM?.toFloat(),
				datum = processed.datum,
				source = processed.source,
				conversionStatus = processed.conversionStatus,
				modelVersion = processed.modelVersion,
				estimatorVersion = processed.estimatorVersion,
				calibrationVersion = processed.calibrationVersion,
				rawAltitudeDatum = processed.rawAltitudeDatum,
				clockDomainId = locationResult.lastFixMetadata.clockDomainId,
			)
		} else {
			ProcessedAltitudeData(
				altitudeM = null,
				datum = AltitudeDatum.UNKNOWN_LEGACY,
				source = AltitudeSource.UNKNOWN_LEGACY,
				conversionStatus = AltitudeConversionStatus.NOT_ATTEMPTED,
				rawAltitudeDatum = if (lastRawGpsAltitudeM != null) {
					AltitudeDatum.WGS84_ELLIPSOID
				} else {
					AltitudeDatum.UNKNOWN_LEGACY
				},
				clockDomainId = locationResult.lastFixMetadata.clockDomainId,
			)
		}

		collectionData.setLocation(location)
		collectionData.distanceFromPreviousM = distanceFromPrevious
		lastAcceptedLocation = Location(location)
	}

	companion object {
		private const val MAX_ALLOWED_DIFFERENCE_TO_COMPUTED = 0.2f
		private const val SPEED_SMOOTHING_ALPHA = 0.4f
		// Human-powered movement is typically well below ~45 km/h walking or ~120 km/h cycling,
		// and normal road travel is usually below ~300 km/h. Anything above 500 km/h is treated
		// as a GPS teleport and discarded entirely so it cannot inflate speed or distance.
		private const val MAX_ABSOLUTE_SPEED_METERS_PER_SECOND = 500f / 3.6f

		// Absolute distance guard: reject any single location update that jumps more
		// than 10 km from the previous accepted position. At 300 km/h highway speed
		// with a 1-second update interval, distance per update is ~83 m, so 10 km
		// provides a very generous margin while still catching cross-continent jumps.
		private const val MAX_JUMP_DISTANCE_METERS = 10_000f

		// When elapsed time between two fixes cannot be determined, use a tighter
		// distance threshold to avoid silently accepting suspicious jumps.
		private const val UNKNOWN_TIME_JUMP_DISTANCE_METERS = 1_000f

		/**
		 * Key for raw GPS altitude (legacy, retained for reference).
		 * Used by PersistenceProcessor to persist the unprocessed altitude.
		 */
		const val RAW_GPS_ALTITUDE_KEY = "raw_gps_altitude"
	}
}
