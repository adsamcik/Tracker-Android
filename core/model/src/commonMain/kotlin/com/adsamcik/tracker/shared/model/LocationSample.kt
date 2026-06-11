package com.adsamcik.tracker.shared.model

/**
 * Room-free raw location sample captured during tracking.
 */
data class LocationSample(
val id: Long = 0,
val timeMs: Long,
val elapsedRealtimeNanos: Long,
val latE7: Int?,
val lonE7: Int?,
val altitudeM: Float?,
val rawGpsAltitudeM: Float?,
val hAccM: Float?,
val vAccM: Float?,
val speedMps: Float?,
val speedAccuracyMps: Float?,
val provider: String,
val quality: SampleQuality,
val motionState: MotionState?,
val policy: String?,
val bucketId: Long?,
val createdAt: Long,
)

/**
 * Sample quality classification based on accuracy and provider.
 */
enum class SampleQuality {
HIGH,
MEDIUM,
LOW,
COARSE,
}

/**
 * Motion state classification at capture time.
 */
enum class MotionState {
MOVING,
STILL,
UNKNOWN,
}
