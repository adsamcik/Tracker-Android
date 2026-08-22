package com.adsamcik.tracker.tracker.source.model

interface SourcePayload {
	val source: SourceKind
}

data class LocationFixPayload(
	val latitudeDegrees: Double,
	val longitudeDegrees: Double,
	val horizontalAccuracyMeters: Float,
	val altitudeMeters: Double?,
	val verticalAccuracyMeters: Float?,
	val speedMetersPerSecond: Float?,
	val bearingDegrees: Float?,
	val provider: String,
) : SourcePayload {
	override val source: SourceKind = SourceKind.LOCATION
}

data class ActivityTransitionPayload(
	val activityType: Int,
	val transitionType: Int,
	val providerElapsedRealtimeNanos: Long,
) : SourcePayload {
	override val source: SourceKind = SourceKind.ACTIVITY
}

data class ActivityRecognitionPayload(
	val activityType: Int,
	val confidencePercent: Int,
	val providerElapsedRealtimeNanos: Long?,
) : SourcePayload {
	override val source: SourceKind = SourceKind.ACTIVITY
}

/** Stable on-disk activity codes shared by activity ingress and motion-policy consumers. */
object StableActivityTypeCode {
	const val STILL = 0
	const val WALKING = 1
	const val RUNNING = 2
	const val ON_BICYCLE = 3
	const val IN_VEHICLE = 4
	const val ON_FOOT = 5
	const val TILTING = 6
	const val UNKNOWN = 7
}

data class StepCounterWindowPayload(
	val bootClockDomainId: String,
	val firstCumulativeCount: Long,
	val lastCumulativeCount: Long,
	val deltaCount: Long,
	val windowStartElapsedRealtimeNanos: Long,
	val windowEndElapsedRealtimeNanos: Long,
	val firstProviderSequence: Long,
	val lastProviderSequence: Long,
	val baselineReset: Boolean,
) : SourcePayload {
	override val source: SourceKind = SourceKind.STEPS
}

data class PressureWindowPayload(
	val sampleCount: Int,
	val meanHectopascals: Double,
	val sumSquaredDeviations: Double,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val windowStartElapsedRealtimeNanos: Long,
	val windowEndElapsedRealtimeNanos: Long,
	val firstProviderSequence: Long,
	val lastProviderSequence: Long,
) : SourcePayload {
	override val source: SourceKind = SourceKind.PRESSURE
}

data class WifiScanAttemptPayload(
	val attemptId: String,
	val outcome: WifiScanAttemptOutcome,
	val resultCount: Int?,
	val resultAgeMs: Long?,
) : SourcePayload {
	override val source: SourceKind = SourceKind.WIFI
}

enum class WifiScanAttemptOutcome {
	REQUESTED,
	ACCEPTED,
	REJECTED,
	RESULTS_AVAILABLE,
	THROTTLED,
	PERMISSION_BLOCKED,
	LOCATION_SERVICES_DISABLED,
	DEFERRED_IDLE,
	PROVIDER_FAILED,
}

data class WifiResultSnapshotPayload(
	val accessPoints: List<WifiAccessPointEvidence>,
	val platformTimestampMs: Long?,
	val resultAgeMs: Long?,
) : SourcePayload {
	override val source: SourceKind = SourceKind.WIFI
}

data class WifiAccessPointEvidence(
	val identifierToken: String,
	val frequencyMhz: Int,
	val signalLevelDbm: Int,
	/** Elapsed-realtime timestamp at which this individual AP was last seen by the provider. */
	val providerTimestampNanos: Long? = null,
)

data class CellSnapshotPayload(
	val subscriptionId: Int?,
	val observations: List<CellObservationEvidence>,
	val refreshOutcome: CellRefreshOutcome,
) : SourcePayload {
	override val source: SourceKind = SourceKind.CELL
}

data class CellObservationEvidence(
	val identifierToken: String,
	val radioType: String,
	val registered: Boolean,
	val signalLevelDbm: Int?,
	val providerTimestampNanos: Long?,
)

enum class CellRefreshOutcome {
	CALLBACK,
	CACHED,
	REFRESH_REQUESTED,
	REFRESH_TIMEOUT,
	THROTTLED,
	PERMISSION_BLOCKED,
	RADIO_UNAVAILABLE,
	PROVIDER_FAILED,
}
