package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.producer.PressureReading

/**
 * Typed envelope for one collection cycle's worth of sensor data.
 * Immutable — constructed from [TrackingCycleBuilder] after all producers finish.
 */
internal data class TrackingCycle(
	val timestampMs: Long,
	val elapsedRealtimeNanos: Long,
	val activity: ActivityInfo? = null,
	val location: LocationData? = null,
	val cellScan: CellScanData? = null,
	val wifiScan: WifiScanData? = null,
	val stepDelta: Int? = null,
	val totalStepsSinceBoot: Long? = null,
	val pressure: PressureReading? = null,
	val rawGpsAltitude: Double? = null,
)
