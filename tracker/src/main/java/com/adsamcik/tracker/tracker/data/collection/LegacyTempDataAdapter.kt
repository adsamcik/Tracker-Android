package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.component.producer.BarometerDataProducer
import com.adsamcik.tracker.tracker.component.producer.StepDataProducer

/**
 * Bridge adapter: creates a [MutableCollectionTempData] from a [TrackingCycle].
 * This is the current bridge while old components still read from TempData.
 * Will be removed when all consumers migrate to [TrackingCycle] directly.
 */
internal object LegacyTempDataAdapter {

	private const val RAW_GPS_ALTITUDE_KEY = "raw_gps_altitude"

	fun toTempData(cycle: TrackingCycle): MutableCollectionTempData {
		val tempData = MutableCollectionTempData(cycle.timestampMs, cycle.elapsedRealtimeNanos)
		populateTempData(tempData, cycle)
		return tempData
	}

	/**
	 * Writes [cycle] fields into an existing [tempData] instance.
	 * Used by [DataProducerManager] to bridge typed cycle data back
	 * into the legacy string-keyed map for old consumers.
	 */
	fun populateTempData(tempData: MutableCollectionTempData, cycle: TrackingCycle) {
		cycle.activity?.let { tempData.setActivity(it) }
		cycle.location?.let { tempData.setLocationData(it) }
		cycle.cellScan?.let { tempData.setCellData(it) }
		cycle.wifiScan?.let { tempData.set(TrackerComponentRequirement.WIFI.name, it) }
		cycle.stepDelta?.let { tempData.set(StepDataProducer.NEW_STEPS_ARG, it) }
		cycle.pressure?.let { tempData.set(BarometerDataProducer.PRESSURE_KEY, it) }
		cycle.rawGpsAltitude?.let { tempData.set(RAW_GPS_ALTITUDE_KEY, it) }
	}
}
