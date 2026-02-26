package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.altitude.AltitudeProcessor
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.PressureReading
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import kotlin.math.abs

internal class LocationTrackerComponent : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(
			TrackerComponentRequirement.LOCATION
	)

	private var altitudeProcessor: AltitudeProcessor? = null
	private var context: Context? = null

	/**
	 * Raw GPS altitude (before fusion) from the most recent location update.
	 * Null if the location had no altitude or the component is not enabled.
	 */
	var lastRawGpsAltitudeM: Double? = null
		private set

	override suspend fun onEnable(context: Context) {
		this.context = context.applicationContext
		altitudeProcessor = AltitudeProcessor()
	}

	override suspend fun onDisable(context: Context) {
		altitudeProcessor?.reset()
		altitudeProcessor = null
		this.context = null
		lastRawGpsAltitudeM = null
	}


	private fun calculateSpeed(prevLocation: Location, location: Location): Float {
		val recordedSpeed = location.speed
		val distance = location.distanceTo(prevLocation)
		val deltaS = (location.elapsedRealtimeNanos - prevLocation.elapsedRealtimeNanos) / Time.SECOND_IN_NANOSECONDS
		val calculatedSpeed = distance / deltaS

		if (recordedSpeed <= 0f) return calculatedSpeed

		val changePercentage = abs(calculatedSpeed - recordedSpeed) / recordedSpeed

		return if (changePercentage >= MAX_ALLOWED_DIFFERENCE_TO_COMPUTED) {
			calculatedSpeed
		} else {
			recordedSpeed
		}
	}

	override suspend fun onDataUpdated(
			tempData: CollectionTempData,
			collectionData: MutableCollectionData
	) {
		val locationResult = tempData.getLocationData(this)

		val location = locationResult.lastLocation

		// Capture raw GPS altitude before any processing
		lastRawGpsAltitudeM = if (location.hasAltitude()) location.altitude else null

		// Apply altitude processing pipeline (geoid correction + accuracy gating + Kalman fusion)
		val ctx = context
		val processor = altitudeProcessor
		if (ctx != null && processor != null) {
			// Get barometer pressure from tempData if available
			val pressureReading = tempData.tryGet<PressureReading>(BarometerDataProducer.PRESSURE_KEY)
			val processedAltitude = processor.processWithBarometer(
				ctx, location, pressureReading?.pressureHpa
			)
			if (processedAltitude != null) {
				location.altitude = processedAltitude
			} else if (location.hasAltitude()) {
				// Altitude failed quality gate — remove it to prevent noisy data propagation
				location.removeAltitude()
			}
		}

		// Store raw GPS altitude in tempData for downstream consumers (e.g., RawLocationWriter)
		if (tempData is MutableCollectionTempData) {
			val rawAlt = lastRawGpsAltitudeM
			if (rawAlt != null) {
				tempData.set(RAW_GPS_ALTITUDE_KEY, rawAlt)
			}
		}

		collectionData.setLocation(location)
	}

	companion object {
		private const val MAX_ALLOWED_DIFFERENCE_TO_COMPUTED = 0.2f

		/**
		 * Key for storing raw GPS altitude in CollectionTempData.
		 * Used by RawLocationWriter to persist the unprocessed altitude.
		 */
		const val RAW_GPS_ALTITUDE_KEY = "raw_gps_altitude"
	}
}

