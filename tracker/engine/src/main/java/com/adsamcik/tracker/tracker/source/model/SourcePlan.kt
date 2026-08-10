package com.adsamcik.tracker.tracker.source.model

sealed interface SourcePlan {
	val source: SourceKind
	val revision: Long
	val enabled: Boolean
}

enum class LocationBackend { FUSED, FRAMEWORK }
enum class LocationMode { DISABLED, PASSIVE, LOW_POWER, BALANCED, HIGH_ACCURACY, PROBE }

data class LocationPlan(
	override val revision: Long,
	val backend: LocationBackend,
	val mode: LocationMode,
	val requestedIntervalMs: Long,
	val minimumUpdateIntervalMs: Long,
	val minimumDisplacementMeters: Float,
	val maximumBatchDelayMs: Long,
	val probeDurationMs: Long? = null,
	val preciseLocationAvailable: Boolean,
) : SourcePlan {
	override val source: SourceKind = SourceKind.LOCATION
	override val enabled: Boolean get() = mode != LocationMode.DISABLED
}

enum class ActivityMode { OFF, TRANSITIONS_ONLY, CONTINUOUS_RECOGNITION }

data class ActivityPlan(
	override val revision: Long,
	val mode: ActivityMode,
	val desiredDetectionLatencyMs: Long,
	val confidenceThresholdPercent: Int,
	val transitionTypes: Set<Int>,
) : SourcePlan {
	override val source: SourceKind = SourceKind.ACTIVITY
	override val enabled: Boolean get() = mode != ActivityMode.OFF
}

data class StepsPlan(
	override val revision: Long,
	override val enabled: Boolean,
	val maximumReportLatencyMs: Long,
	val projectionCheckpointIntervalMs: Long,
	val movementPolicyNeedsLowLatency: Boolean,
) : SourcePlan {
	override val source: SourceKind = SourceKind.STEPS
}

data class PressurePlan(
	override val revision: Long,
	override val enabled: Boolean,
	val hardwareSamplePeriodMicros: Int,
	val maximumReportLatencyMicros: Int,
	val aggregationWindowMs: Long,
	val movementGatedBurst: Boolean,
) : SourcePlan {
	override val source: SourceKind = SourceKind.PRESSURE
}

enum class WifiMode { OFF, CACHED_ONLY, BROADCAST_DRIVEN, ACTIVE_ATTEMPTS }

data class RetryBackoff(
	val initialDelayMs: Long,
	val maximumDelayMs: Long,
	val multiplier: Double = 2.0,
) {
	init {
		require(initialDelayMs >= 0L)
		require(maximumDelayMs >= initialDelayMs)
		require(multiplier >= 1.0)
	}
}

data class WifiPlan(
	override val revision: Long,
	val mode: WifiMode,
	val minimumAttemptIntervalMs: Long,
	val maximumAcceptableResultAgeMs: Long,
	val unchangedResultDedupeWindowMs: Long,
	val backoff: RetryBackoff,
) : SourcePlan {
	override val source: SourceKind = SourceKind.WIFI
	override val enabled: Boolean get() = mode != WifiMode.OFF
}

enum class CellMode { OFF, OBSERVE_CHANGES, OBSERVE_AND_SPARSE_REFRESH }

data class CellPlan(
	override val revision: Long,
	val mode: CellMode,
	val minimumRefreshAttemptIntervalMs: Long,
	val maximumAcceptableCachedAgeMs: Long,
	val subscriptionIds: Set<Int>,
	val backoff: RetryBackoff,
) : SourcePlan {
	override val source: SourceKind = SourceKind.CELL
	override val enabled: Boolean get() = mode != CellMode.OFF
}

data class AcquisitionPlanRevision(
	val revision: Long,
	val planId: String,
	val createdAtMs: Long,
	val plans: Map<SourceKind, SourcePlan>,
) {
	init {
		require(revision >= 0L)
		require(planId.isNotBlank())
		require(createdAtMs >= 0L)
		require(plans.all { (source, plan) -> source == plan.source && plan.revision == revision }) {
			"Every source plan must match its map key and desired-plan revision"
		}
	}
}

data class AppliedSourcePlan(
	val desiredRevision: Long,
	val appliedRevision: Long?,
	val source: SourceKind,
	val sourceInstanceId: SourceInstanceId?,
	val registrationGeneration: Long?,
	val appliedAtElapsedRealtimeNanos: Long?,
	val status: SourceApplyStatus,
	val degradedReasons: Set<SourceDegradedReason> = emptySet(),
)

enum class SourceApplyStatus { APPLIED, DEGRADED, ROLLED_BACK, BLOCKED, FAILED }

enum class SourceDegradedReason {
	PERMISSION_MISSING,
	PROVIDER_UNAVAILABLE,
	HARDWARE_UNAVAILABLE,
	BACKGROUND_START_ILLEGAL,
	FOREGROUND_CAPABILITY_MISSING,
	POWER_SAVER,
	THERMAL,
	DOZE,
	PLATFORM_THROTTLED,
}

