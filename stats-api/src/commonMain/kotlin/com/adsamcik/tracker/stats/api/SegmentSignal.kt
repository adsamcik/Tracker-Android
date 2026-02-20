package com.adsamcik.tracker.stats.api

/**
 * Per-cycle input signal for the session segment detector.
 *
 * Each tracking cycle produces one signal from the collected sensor data.
 * Nullable fields indicate the sensor was unavailable that cycle.
 *
 * @property timestampMs Wall clock time of this collection cycle
 * @property latE7 Latitude in E7 format (degrees * 1e7), null if no GPS fix
 * @property lonE7 Longitude in E7 format (degrees * 1e7), null if no GPS fix
 * @property horizontalAccuracyM GPS horizontal accuracy in meters, null if unavailable
 * @property speedMps Speed over ground in m/s, null if unavailable
 * @property stepDelta Steps counted since previous signal (0 if no step sensor)
 * @property activityType Detected activity (maps to DetectedActivityType), null if unavailable
 * @property activityConfidence Activity recognition confidence 0-100, null if unavailable
 * @property distanceDeltaM Distance from previous signal in meters, null if no previous location
 */
data class SegmentSignal(
	val timestampMs: Long,
	val latE7: Int? = null,
	val lonE7: Int? = null,
	val horizontalAccuracyM: Float? = null,
	val speedMps: Float? = null,
	val stepDelta: Int = 0,
	val activityType: DetectedActivityType? = null,
	val activityConfidence: Int? = null,
	val distanceDeltaM: Float? = null,
)
