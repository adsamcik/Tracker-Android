package com.adsamcik.tracker.tracker.data.collection

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.tracker.component.producer.PressureReading

/**
 * Thread-safe builder for [TrackingCycle].
 * Each producer writes to its own [Volatile] field — no locking needed
 * because each field has exactly one writer.
 * After all producers complete, call [build] to get the immutable snapshot.
 */
internal class TrackingCycleBuilder(
	val timestampMs: Long,
	val elapsedRealtimeNanos: Long,
) {
	@Volatile
	var activity: ActivityInfo? = null

	@Volatile
	var location: LocationData? = null

	@Volatile
	var cellScan: CellScanData? = null

	@Volatile
	var wifiScan: WifiScanData? = null

	@Volatile
	var stepDelta: Int? = null

	@Volatile
	var totalStepsSinceBoot: Long? = null

	@Volatile
	var pressure: PressureReading? = null

	@Volatile
	var rawGpsAltitude: Double? = null

	fun build(): TrackingCycle = TrackingCycle(
		timestampMs = timestampMs,
		elapsedRealtimeNanos = elapsedRealtimeNanos,
		activity = activity,
		location = location,
		cellScan = cellScan,
		wifiScan = wifiScan,
		stepDelta = stepDelta,
		totalStepsSinceBoot = totalStepsSinceBoot,
		pressure = pressure,
		rawGpsAltitude = rawGpsAltitude,
	)
}
