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
/** Datum for [altitudeM]; unknown historical/imported values must not be presented as MSL. */
val altitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
/** Source actually used for [altitudeM]. */
val altitudeSource: AltitudeSource = AltitudeSource.UNKNOWN_LEGACY,
/** Android-model conversion outcome captured with this processed result. */
val altitudeConversionStatus: AltitudeConversionStatus = AltitudeConversionStatus.UNKNOWN_LEGACY,
/** Datum for [rawGpsAltitudeM], normally WGS-84 ellipsoid for fresh Android provider evidence. */
val rawGpsAltitudeDatum: AltitudeDatum = AltitudeDatum.UNKNOWN_LEGACY,
/** Version of the model/geoid contract used for [altitudeM]. */
val altitudeModelVersion: Int = 0,
/** Unmodified provider speed and course evidence, separate from the curated estimate. */
val rawPlatformSpeedMps: Float? = null,
val rawPlatformSpeedAccuracyMps: Float? = null,
val bearingDeg: Float? = null,
val bearingAccuracyDeg: Float? = null,
val bootClockDomainId: String? = null,
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
