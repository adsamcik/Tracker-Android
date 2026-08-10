package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.control.CollectionAcquisitionProfile
import com.adsamcik.tracker.tracker.source.control.CollectionMotionPolicy
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject

data class SourceConstraint(
	val hardwareAvailable: Boolean = true,
	val providerAvailable: Boolean = true,
	val permissionGranted: Boolean = true,
	val foregroundCapabilityLegal: Boolean = true,
	val backgroundStartLegal: Boolean = true,
)

data class PlanResolutionContext(
	val constraints: Map<SourceKind, SourceConstraint>,
	val powerSaver: Boolean,
	val doze: Boolean,
	val severeThermalPressure: Boolean,
	val motionProfile: CollectionAcquisitionProfile = CollectionMotionPolicy.unrestricted().acquisitionProfile,
)

data class ResolvedAcquisitionPlan(
	val desired: AcquisitionPlanRevision,
	val applicablePlans: Map<SourceKind, SourcePlan>,
	val degradedReasons: Map<SourceKind, Set<SourceDegradedReason>>,
	val dominantDemand: Map<SourceKind, SourceDemand>,
) {
	val fullyApplicable: Boolean get() = degradedReasons.values.all(Set<SourceDegradedReason>::isEmpty)
}

class SourcePlanResolver @Inject constructor() {
	fun resolve(
		desired: AcquisitionPlanRevision,
		demands: Collection<SourceDemand>,
		context: PlanResolutionContext,
	): ResolvedAcquisitionPlan {
		val dominant = demands.groupBy(SourceDemand::source).mapValues { (_, sourceDemands) ->
			sourceDemands.minWith(
				compareBy<SourceDemand>(SourceDemand::desiredLatencyMs)
					.thenBy(SourceDemand::maximumAgeMs)
					.thenByDescending { demand -> demand.quality.ordinal },
			)
		}
		val degradation = mutableMapOf<SourceKind, Set<SourceDegradedReason>>()
		val constrainedPlans = desired.plans.mapValues { (source, plan) ->
			val reasons = mutableSetOf<SourceDegradedReason>()
			val constraint = context.constraints[source] ?: SourceConstraint()
			if (!constraint.hardwareAvailable) reasons += SourceDegradedReason.HARDWARE_UNAVAILABLE
			if (!constraint.providerAvailable) reasons += SourceDegradedReason.PROVIDER_UNAVAILABLE
			if (!constraint.permissionGranted) reasons += SourceDegradedReason.PERMISSION_MISSING
			if (!constraint.foregroundCapabilityLegal) reasons += SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING
			if (!constraint.backgroundStartLegal) reasons += SourceDegradedReason.BACKGROUND_START_ILLEGAL

			val blocked = reasons.any { reason ->
				reason == SourceDegradedReason.HARDWARE_UNAVAILABLE ||
					reason == SourceDegradedReason.PROVIDER_UNAVAILABLE ||
					reason == SourceDegradedReason.PERMISSION_MISSING ||
					reason == SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING ||
					reason == SourceDegradedReason.BACKGROUND_START_ILLEGAL
			}
			var applicable = if (blocked) plan.disabled() else plan.applyDemand(dominant[source])
			if (context.powerSaver) {
				when (applicable) {
					is LocationPlan -> if (applicable.mode == LocationMode.HIGH_ACCURACY) {
						reasons += SourceDegradedReason.POWER_SAVER
						applicable = applicable.copy(mode = LocationMode.BALANCED)
					}
					is CellPlan -> if (applicable.mode == CellMode.OBSERVE_AND_SPARSE_REFRESH) {
						reasons += SourceDegradedReason.POWER_SAVER
						applicable = applicable.copy(mode = CellMode.OBSERVE_CHANGES)
					}
					else -> Unit
				}
			}
			if (context.doze && applicable is WifiPlan && applicable.mode == WifiMode.ACTIVE_ATTEMPTS) {
				reasons += SourceDegradedReason.DOZE
			}
			if (context.severeThermalPressure && applicable is PressurePlan && applicable.enabled) {
				reasons += SourceDegradedReason.THERMAL
				applicable = applicable.copy(enabled = false)
			}
			degradation[source] = reasons
			applicable
		}
		val activityWakeAvailable = (constrainedPlans[SourceKind.ACTIVITY] as? ActivityPlan)?.enabled == true
		val plans = constrainedPlans.mapValues { (_, plan) ->
			plan.applyMotionPolicy(context.motionProfile, activityWakeAvailable)
		}
		return ResolvedAcquisitionPlan(
			desired = desired,
			applicablePlans = plans,
			degradedReasons = degradation,
			dominantDemand = dominant,
		)
	}

	/** Tightens an enabled user plan to satisfy policy freshness without re-enabling an off source. */
	private fun SourcePlan.applyDemand(demand: SourceDemand?): SourcePlan {
		if (demand == null || !enabled) return this
		val latencyMs = demand.desiredLatencyMs.coerceAtLeast(1L)
		val ageMs = demand.maximumAgeMs.coerceAtLeast(1L)
		return when (this) {
			is LocationPlan -> copy(
				mode = when {
					demand.quality == EvidenceQuality.HIGH -> LocationMode.HIGH_ACCURACY
					demand.quality == EvidenceQuality.BALANCED && mode in setOf(LocationMode.PASSIVE, LocationMode.LOW_POWER) ->
						LocationMode.BALANCED
					else -> mode
				},
				requestedIntervalMs = minOf(requestedIntervalMs, ageMs),
				minimumUpdateIntervalMs = minOf(minimumUpdateIntervalMs, latencyMs),
				maximumBatchDelayMs = minOf(maximumBatchDelayMs, latencyMs),
			)
			is ActivityPlan -> copy(
				mode = if (demand.quality == EvidenceQuality.HIGH) {
					ActivityMode.CONTINUOUS_RECOGNITION
				} else mode,
				desiredDetectionLatencyMs = minOf(desiredDetectionLatencyMs, latencyMs),
			)
			is StepsPlan -> copy(
				maximumReportLatencyMs = minOf(maximumReportLatencyMs, latencyMs),
				projectionCheckpointIntervalMs = minOf(projectionCheckpointIntervalMs, ageMs),
				movementPolicyNeedsLowLatency = movementPolicyNeedsLowLatency || demand.quality == EvidenceQuality.HIGH,
			)
			is PressurePlan -> copy(
				maximumReportLatencyMicros = minOf(
					maximumReportLatencyMicros.toLong(),
					latencyMs.coerceAtMost(Int.MAX_VALUE / 1_000L) * 1_000L,
				).toInt(),
				aggregationWindowMs = minOf(aggregationWindowMs, ageMs),
			)
			is WifiPlan -> copy(
				minimumAttemptIntervalMs = minOf(minimumAttemptIntervalMs, latencyMs),
				maximumAcceptableResultAgeMs = minOf(maximumAcceptableResultAgeMs, ageMs),
			)
			is CellPlan -> copy(
				minimumRefreshAttemptIntervalMs = minOf(minimumRefreshAttemptIntervalMs, latencyMs),
				maximumAcceptableCachedAgeMs = minOf(maximumAcceptableCachedAgeMs, ageMs),
			)
		}
	}

	private fun SourcePlan.disabled(): SourcePlan = when (this) {
		is LocationPlan -> copy(mode = LocationMode.DISABLED)
		is ActivityPlan -> copy(mode = ActivityMode.OFF)
		is StepsPlan -> copy(enabled = false)
		is PressurePlan -> copy(enabled = false)
		is WifiPlan -> copy(mode = WifiMode.OFF)
		is CellPlan -> copy(mode = CellMode.OFF)
	}

	/**
	 * Reduces only enabled plans. User-disabled sources remain disabled, and full fidelity is
	 * restored from the immutable desired plan as soon as fused motion evidence says moving.
	 */
	private fun SourcePlan.applyMotionPolicy(
		profile: CollectionAcquisitionProfile,
		activityWakeAvailable: Boolean,
	): SourcePlan {
		if (!enabled) return this
		return when (this) {
			is LocationPlan -> when (profile.locationStrategy) {
				LocationCollectionStrategy.FULL_FIDELITY -> this
				LocationCollectionStrategy.LOW_POWER_SENTINEL -> lowPowerSentinel()
				LocationCollectionStrategy.PASSIVE_WHILE_STATIONARY -> if (activityWakeAvailable) {
					copy(
						mode = LocationMode.PASSIVE,
						requestedIntervalMs = maxOf(requestedIntervalMs, 60_000L),
						minimumUpdateIntervalMs = maxOf(minimumUpdateIntervalMs, 30_000L),
						minimumDisplacementMeters = maxOf(minimumDisplacementMeters, 25f),
						maximumBatchDelayMs = maxOf(maximumBatchDelayMs, 120_000L),
						probeDurationMs = null,
					)
				} else {
					// Without Activity Transition as a wake signal, passive-only location could miss
					// vehicle motion. Keep a low-power sentinel instead.
					lowPowerSentinel()
				}
			}
			is ActivityPlan -> if (
				profile.stationary &&
				mode == ActivityMode.CONTINUOUS_RECOGNITION
			) copy(mode = ActivityMode.TRANSITIONS_ONLY) else this
			is StepsPlan -> if (!profile.lowLatencyStepReporting) copy(
				maximumReportLatencyMs = maxOf(maximumReportLatencyMs, 120_000L),
				projectionCheckpointIntervalMs = maxOf(projectionCheckpointIntervalMs, 60_000L),
				movementPolicyNeedsLowLatency = false,
			) else this
			is PressurePlan -> if (!profile.continuousPressureAllowed && activityWakeAvailable) {
				copy(enabled = false)
			} else this
			is WifiPlan -> if (!profile.expensiveNetworkScansAllowed && mode == WifiMode.ACTIVE_ATTEMPTS) {
				copy(mode = WifiMode.BROADCAST_DRIVEN)
			} else this
			is CellPlan -> if (!profile.expensiveNetworkScansAllowed && mode == CellMode.OBSERVE_AND_SPARSE_REFRESH) {
				copy(mode = CellMode.OBSERVE_CHANGES)
			} else this
		}
	}

	private fun LocationPlan.lowPowerSentinel() = copy(
		mode = LocationMode.LOW_POWER,
		requestedIntervalMs = maxOf(requestedIntervalMs, 30_000L),
		minimumUpdateIntervalMs = maxOf(minimumUpdateIntervalMs, 15_000L),
		minimumDisplacementMeters = maxOf(minimumDisplacementMeters, 10f),
		maximumBatchDelayMs = maxOf(maximumBatchDelayMs, 60_000L),
		probeDurationMs = null,
	)
}
