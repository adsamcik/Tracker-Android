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
val receivedElapsedRealtimeNanos: Long = 0L,
val deliveryAgeMs: Long? = null,
val acquisitionMode: String = "UNKNOWN",
val requestPriority: String = "UNKNOWN",
val permissionPrecision: String = "UNKNOWN",
val batchIndex: Int = 0,
val batchSize: Int = 1,
val isMock: Boolean = false,
val estimatorVersion: Int = 1,
val calibrationVersion: Int = 0,
/** Stable pending-signal identity used by the database write path for replay idempotency. */
val sourceSignalId: String? = null,
/** Immutable provider-fix identity used to link this curated sample to raw evidence. */
val sourceEventId: String? = null,
/** Conservative monotonic-clock domain associated with [elapsedRealtimeNanos]. */
val clockDomainId: String? = null,
/** Source snapshot revision assigned atomically with persistence. */
val sourceRevision: Long = 0L,
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
