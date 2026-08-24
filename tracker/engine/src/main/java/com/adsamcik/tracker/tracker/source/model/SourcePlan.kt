package com.adsamcik.tracker.tracker.source.model

import java.security.MessageDigest

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
	val sourcePolicyRevision: Long? = null,
) {
	init {
		require(revision >= 0L)
		require(planId.isNotBlank())
		require(createdAtMs >= 0L)
		require(sourcePolicyRevision == null || sourcePolicyRevision > 0L)
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
	DEMAND_FLOOR_UNSATISFIED,
}

/** Maximum provider-item age admitted by the plan, independent of acquisition cadence. */
internal fun SourcePlan.providerItemMaximumAgeMs(): Long = when (this) {
	is LocationPlan -> maxOf(requestedIntervalMs, maximumBatchDelayMs)
	is ActivityPlan -> desiredDetectionLatencyMs
	is StepsPlan -> maximumReportLatencyMs
	is PressurePlan -> maxOf(maximumReportLatencyMicros.toLong().microsToMillisCeiling(), aggregationWindowMs)
	is WifiPlan -> maximumAcceptableResultAgeMs
	is CellPlan -> maximumAcceptableCachedAgeMs
}

/** Provider delivery latency when Android offers a bounded request; callbacks are opportunistic. */
internal fun SourcePlan.providerDeliveryLatencyMsOrNull(): Long? = when (this) {
	is LocationPlan -> maxOf(requestedIntervalMs, maximumBatchDelayMs)
	is ActivityPlan -> if (mode == ActivityMode.CONTINUOUS_RECOGNITION) desiredDetectionLatencyMs else null
	is StepsPlan -> maximumReportLatencyMs
	is PressurePlan -> maximumReportLatencyMicros.toLong().microsToMillisCeiling()
	is WifiPlan -> null
	is CellPlan -> null
}

internal fun SourcePlan.satisfies(demand: SourceDemand): Boolean {
	val floor = demand.acquisitionFloor ?: return true
	if (!enabled || source != demand.source || floor.source != source) return false
	if (!satisfiesAcquisitionFloor(floor)) return false
	if (providerItemMaximumAgeMs() > demand.maximumAgeMs) return false
	val requestedLatency = demand.requestedDeliveryLatencyMs ?: return true
	return providerDeliveryLatencyMsOrNull()?.let { it <= requestedLatency } == true
}

private fun Long.microsToMillisCeiling(): Long = (this + 999L) / 1_000L

private fun SourcePlan.satisfiesAcquisitionFloor(floor: SourceAcquisitionFloor): Boolean = when {
	this is LocationPlan && floor is LocationAcquisitionFloor ->
		locationFloorModeOrNull()?.level?.let { it >= floor.minimumMode.level } == true
	this is ActivityPlan && floor is ActivityAcquisitionFloor ->
		providedActivityCapabilities().containsAll(floor.requiredCapabilities)
	this is StepsPlan && floor is StepsAcquisitionFloor ->
		enabled &&
			floor.mechanism == StepsAcquisitionMechanism.DIRECT_COUNTER &&
			!floor.continuousCoverageRequired &&
			maximumReportLatencyMs <= floor.maximumReportLatencyMs
	this is PressurePlan && floor is PressureAcquisitionFloor ->
		enabled &&
			hardwareSamplePeriodMicros <= floor.maximumSamplePeriodMicros &&
			maximumReportLatencyMicros <= floor.maximumReportLatencyMicros &&
			aggregationWindowMs <= floor.maximumAggregationWindowMs
	this is WifiPlan && floor === WifiBroadcastAcquisitionFloor ->
		mode == WifiMode.BROADCAST_DRIVEN || mode == WifiMode.ACTIVE_ATTEMPTS
	this is CellPlan && floor === CellCallbackAcquisitionFloor ->
		mode == CellMode.OBSERVE_CHANGES || mode == CellMode.OBSERVE_AND_SPARSE_REFRESH
	else -> false
}

internal fun ActivityPlan.providedActivityCapabilities(): Set<ActivityAcquisitionCapability> = when (mode) {
	ActivityMode.OFF -> emptySet()
	ActivityMode.TRANSITIONS_ONLY -> setOf(ActivityAcquisitionCapability.TRANSITIONS)
	ActivityMode.CONTINUOUS_RECOGNITION -> setOf(ActivityAcquisitionCapability.CLASSIFICATIONS)
}

private fun LocationPlan.locationFloorModeOrNull(): LocationFloorMode? = when (mode) {
	LocationMode.DISABLED,
	LocationMode.PROBE,
	-> null
	LocationMode.PASSIVE -> LocationFloorMode.PASSIVE
	LocationMode.LOW_POWER -> LocationFloorMode.LOW_POWER
	LocationMode.BALANCED -> LocationFloorMode.BALANCED
	LocationMode.HIGH_ACCURACY -> LocationFloorMode.HIGH_ACCURACY
}

/** Stable identity of the Android/provider work, deliberately excluding the global plan revision. */
fun SourcePlan.physicalConfigurationFingerprint(): String {
	val canonical = when (this) {
		is LocationPlan -> listOf(
			source, backend, mode, requestedIntervalMs, minimumUpdateIntervalMs,
			minimumDisplacementMeters, maximumBatchDelayMs, probeDurationMs, preciseLocationAvailable,
		)
		is ActivityPlan -> listOf(
			source, mode, desiredDetectionLatencyMs, confidenceThresholdPercent,
			transitionTypes.sorted().joinToString(","),
		)
		is StepsPlan -> listOf(source, enabled, maximumReportLatencyMs)
		is PressurePlan -> listOf(source, enabled, hardwareSamplePeriodMicros, maximumReportLatencyMicros)
		is WifiPlan -> when (mode) {
			WifiMode.OFF -> listOf(source, mode)
			WifiMode.CACHED_ONLY,
			WifiMode.BROADCAST_DRIVEN,
			-> listOf(source, "CALLBACK_REGISTRATION")
			WifiMode.ACTIVE_ATTEMPTS -> listOf(
				source, "CALLBACK_REGISTRATION", "ACTIVE_PROBE",
				minimumAttemptIntervalMs, backoff.initialDelayMs,
				backoff.maximumDelayMs, backoff.multiplier,
			)
		}
		is CellPlan -> when (mode) {
			CellMode.OFF -> listOf(source, mode)
			CellMode.OBSERVE_CHANGES -> listOf(
				source, "CHANGE_CALLBACK", subscriptionIds.sorted().joinToString(","),
			)
			CellMode.OBSERVE_AND_SPARSE_REFRESH -> listOf(
				source, "CHANGE_CALLBACK", "EXPLICIT_REFRESH",
				subscriptionIds.sorted().joinToString(","), minimumRefreshAttemptIntervalMs,
				backoff.initialDelayMs, backoff.maximumDelayMs, backoff.multiplier,
			)
		}
	}.joinToString("\u001f")
	return MessageDigest.getInstance("SHA-256")
		.digest(canonical.toByteArray(Charsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }
}
