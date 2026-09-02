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
	val boundaryKind: StepBoundaryKind,
) : SourcePayload {
	override val source: SourceKind = SourceKind.STEPS

	/** Compatibility constructor for version 1/2 call sites and the frozen v27 decoder. */
	constructor(
		bootClockDomainId: String,
		firstCumulativeCount: Long,
		lastCumulativeCount: Long,
		deltaCount: Long,
		windowStartElapsedRealtimeNanos: Long,
		windowEndElapsedRealtimeNanos: Long,
		firstProviderSequence: Long,
		lastProviderSequence: Long,
		baselineReset: Boolean,
	) : this(
		bootClockDomainId = bootClockDomainId,
		firstCumulativeCount = firstCumulativeCount,
		lastCumulativeCount = lastCumulativeCount,
		deltaCount = deltaCount,
		windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
		windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
		firstProviderSequence = firstProviderSequence,
		lastProviderSequence = lastProviderSequence,
		boundaryKind = StepBoundaryKind.fromLegacyResetFlag(baselineReset),
	)

	/** Legacy projection compatibility. [boundaryKind] is the only stored source of truth. */
	val baselineReset: Boolean
		get() = boundaryKind != StepBoundaryKind.COVERED
}

/** Stable Steps boundary semantics persisted from source-payload version 3 onward. */
enum class StepBoundaryKind {
	/** First fresh sample in a physical or authorization domain; it contributes no steps. */
	BASELINE,

	/** A complete fresh interval, including both zero and positive [StepCounterWindowPayload.deltaCount]. */
	COVERED,

	/** The cumulative provider counter decreased; the lost interval is an explicit reset gap. */
	COUNTER_RESET,

	/**
	 * A version 1 or 2 reset-shaped payload. Those bytes did not say whether the event was a
	 * baseline or a counter reset, so consumers must retain it as partial instead of guessing.
	 * This decode-only value has no version 3 wire code.
	 */
	LEGACY_AMBIGUOUS,
	;

	companion object {
		fun fromLegacyResetFlag(baselineReset: Boolean): StepBoundaryKind =
			if (baselineReset) LEGACY_AMBIGUOUS else COVERED
	}
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
	/** Extended qualified-window evidence is present from source-payload version 4 onward. */
	val firstHectopascals: Float? = null,
	val lastHectopascals: Float? = null,
	val slopeHectopascalsPerSecond: Double? = null,
	val rSquared: Double? = null,
	val sensorAccuracy: PressureSensorAccuracy = PressureSensorAccuracy.LEGACY_UNAVAILABLE,
	/** Exact provider request after Android sensor-capability normalization. */
	val effectiveSamplePeriodMicros: Int? = null,
	val effectiveMaximumReportLatencyMicros: Int? = null,
	/** Requested aggregation target. Actual coverage remains [sampleCount] plus the window times. */
	val targetWindowDurationNanos: Long? = null,
	val expectedSampleCount: Int? = null,
	val maximumInterSampleGapNanos: Long? = null,
	val closureKind: PressureWindowClosureKind = PressureWindowClosureKind.LEGACY_UNAVAILABLE,
) : SourcePayload {
	override val source: SourceKind = SourceKind.PRESSURE
}

/** Stable, source-owned aggregation of Android's per-event sensor accuracy. */
enum class PressureSensorAccuracy {
	/** Version 1-3 bytes did not carry an accuracy fact. This value is decode-only. */
	LEGACY_UNAVAILABLE,

	/** The current provider event exposed no recognized Android accuracy code. */
	UNKNOWN,

	UNRELIABLE,
	LOW,
	MEDIUM,
	HIGH,
}

/** Why the source-owned Pressure window closed. */
enum class PressureWindowClosureKind {
	/** Version 1-3 bytes did not carry closure semantics. This value is decode-only. */
	LEGACY_UNAVAILABLE,

	/** A later fresh sample proved that the configured target duration had elapsed. */
	TARGET_ELAPSED,

	/** Authorization, lifecycle, provider, or capacity fencing closed a partial source window. */
	SOURCE_BOUNDARY,
}

/** Exact target count implied by one normalized Pressure request, without a floating ratio. */
internal fun expectedPressureSampleCount(
	targetWindowDurationNanos: Long,
	effectiveSamplePeriodMicros: Int,
): Int {
	require(targetWindowDurationNanos > 0L)
	require(effectiveSamplePeriodMicros > 0)
	val samplePeriodNanos = effectiveSamplePeriodMicros.toLong() * 1_000L
	val wholePeriods = targetWindowDurationNanos / samplePeriodNanos
	val roundedUp = wholePeriods + if (targetWindowDurationNanos % samplePeriodNanos == 0L) {
		0L
	} else {
		1L
	}
	val expected = roundedUp.coerceAtLeast(1L)
	require(expected <= Int.MAX_VALUE) { "Pressure target sample count exceeds the durable payload range" }
	return expected.toInt()
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
