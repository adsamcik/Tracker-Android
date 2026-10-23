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
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.LocationFloorMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.satisfies
import javax.inject.Inject
import com.adsamcik.tracker.tracker.policy.BatteryPressure

private const val BALANCED_LOCATION_LEVEL = 3

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
	val locationProfileLevel: Int? = null,
	val locationLiveFocus: Boolean = false,
	val batteryPressure: BatteryPressure = BatteryPressure.NONE,
	/** Only the independent all-day Location owner opts into background cadence reductions. */
	val allDayLocation: Boolean = false,
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
		val directDemands = demands
			.filter { demand -> demand.acquisitionFloor != null }
			.groupBy(SourceDemand::source)
		val degradation = mutableMapOf<SourceKind, Set<SourceDegradedReason>>()
		val constrainedPlans = desired.plans.mapValues { (source, plan) ->
			val reasons = mutableSetOf<SourceDegradedReason>()
			val sourceFloors = directDemands[source].orEmpty()
			val targetSatisfiesFloors = sourceFloors.all(plan::satisfies)
			val adaptationAllowed = sourceFloors.all(SourceDemand::adaptiveReductionAllowed)
			val constraint = context.constraints[source] ?: SourceConstraint()
			if (!constraint.hardwareAvailable) reasons += SourceDegradedReason.HARDWARE_UNAVAILABLE
			if (!constraint.providerAvailable) reasons += SourceDegradedReason.PROVIDER_UNAVAILABLE
			if (!constraint.permissionGranted) reasons += SourceDegradedReason.PERMISSION_MISSING
			if (!constraint.foregroundCapabilityLegal) reasons += SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING
			if (!constraint.backgroundStartLegal) reasons += SourceDegradedReason.BACKGROUND_START_ILLEGAL

			val blocked = reasons.any { reason -> reason.isHardPrerequisite() }
			// The desired plan is the policy-derived QoS ceiling. A demand describes a
			// consumer requirement; it may diagnose degradation, but must never silently
			// upgrade physical acquisition beyond the effective SourcePolicy.
			if (!targetSatisfiesFloors) reasons += SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED
			var applicable = if (blocked || !targetSatisfiesFloors) plan.disabled() else plan
			val canAdapt = !blocked && targetSatisfiesFloors && adaptationAllowed
			if (canAdapt && (context.powerSaver ||
				(source == SourceKind.LOCATION && context.batteryPressure != BatteryPressure.NONE))) {
				val candidate = when (applicable) {
					is LocationPlan -> if (applicable.adaptiveProfileVersion == 1) {
						if (context.locationLiveFocus) applicable else {
							val level = context.locationProfileLevel
							if (level == null) applicable else adaptiveLocationPlan(minOf(level,
                                if (context.batteryPressure == BatteryPressure.STRONG) 1
								else if (context.allDayLocation) 2 else 3),
								applicable.revision, applicable.backend, applicable.preciseLocationAvailable)
						}
					} else if (applicable.mode == LocationMode.HIGH_ACCURACY) {
						applicable.copy(mode = LocationMode.BALANCED)
					} else applicable
					is CellPlan -> if (applicable.mode == CellMode.OBSERVE_AND_SPARSE_REFRESH) {
						applicable.copy(mode = CellMode.OBSERVE_CHANGES)
					} else applicable
					is WifiPlan -> if (applicable.mode == WifiMode.ACTIVE_ATTEMPTS) {
						applicable.copy(mode = WifiMode.BROADCAST_DRIVEN)
					} else applicable
					else -> applicable
				}
				val accepted = applicable.acceptReduction(candidate, sourceFloors)
				if (accepted != applicable) {
					reasons += SourceDegradedReason.POWER_SAVER
					applicable = accepted
				}
			}
			if (context.doze && applicable is WifiPlan && applicable.mode == WifiMode.ACTIVE_ATTEMPTS) {
				reasons += SourceDegradedReason.DOZE
			}
			if (canAdapt && context.severeThermalPressure && applicable is LocationPlan &&
				applicable.adaptiveProfileVersion == 1) {
				if (applicable.enabled) {
					val level = context.locationProfileLevel ?: 1
					val reduced = adaptiveLocationPlan(minOf(level, 1), applicable.revision,
						applicable.backend, applicable.preciseLocationAvailable)
					val accepted = applicable.acceptReduction(reduced, sourceFloors)
					if (accepted != applicable) {
						reasons += SourceDegradedReason.THERMAL
						applicable = accepted
					}
				}
			}
			if (
				canAdapt &&
				context.severeThermalPressure &&
				applicable is PressurePlan &&
				applicable.enabled
			) {
				val candidate = applicable.acceptReduction(
					applicable.minimumUsefulContinuousBatched(),
					sourceFloors,
				)
				if (candidate != applicable) {
					reasons += SourceDegradedReason.THERMAL
					applicable = candidate
				}
			}
			if (blocked && sourceFloors.isNotEmpty()) reasons += SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED
			degradation[source] = reasons
			applicable
		}
		val activityWakeAvailable = (constrainedPlans[SourceKind.ACTIVITY] as? ActivityPlan)?.enabled == true
		val plans = constrainedPlans.mapValues { (source, plan) ->
			val sourceFloors = directDemands[source].orEmpty()
			val adaptationAllowed = sourceFloors.all(SourceDemand::adaptiveReductionAllowed)
			val hardBlocked = degradation.getValue(source).any { reason -> reason.isHardPrerequisite() }
			val floorSatisfied = SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED !in degradation.getValue(source)
			val candidate = if (!hardBlocked && floorSatisfied && adaptationAllowed) {
				if (plan is LocationPlan && plan.adaptiveProfileVersion == 1) {
					// Assistance is optional: stationary reduction always retains an active request.
					if (context.allDayLocation && !context.locationLiveFocus) {
						plan.adaptiveAllDay(context.motionProfile, context.locationProfileLevel)
					} else if (context.motionProfile.stationary) plan.adaptiveStationary() else plan
                } else if (desired.balanceProfileVersion != null) {
                    when {
                        context.motionProfile.stationary && plan is WifiPlan &&
                            plan.mode == WifiMode.ACTIVE_ATTEMPTS -> plan.copy(mode = WifiMode.BROADCAST_DRIVEN)
                        context.motionProfile.stationary && plan is CellPlan &&
                            plan.mode == CellMode.OBSERVE_AND_SPARSE_REFRESH ->
                                plan.copy(mode = CellMode.OBSERVE_CHANGES)
                        else -> plan
                    }
                } else plan.applyMotionPolicy(context.motionProfile, activityWakeAvailable)
			} else plan
			val applicable = plan.acceptReduction(candidate, sourceFloors)
			if (applicable != plan) {
				degradation[source] = degradation.getValue(source) + SourceDegradedReason.MOTION_REDUCTION
			}
			applicable
		}
		(directDemands.keys - desired.plans.keys).forEach { source ->
			degradation[source] = setOf(SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED)
		}
		return ResolvedAcquisitionPlan(
			desired = desired,
			applicablePlans = plans,
			degradedReasons = degradation,
			dominantDemand = dominant,
		)
	}

	private fun SourcePlan.disabled(): SourcePlan = when (this) {
		is LocationPlan -> copy(mode = LocationMode.DISABLED)
		is ActivityPlan -> copy(mode = ActivityMode.OFF)
		is StepsPlan -> copy(enabled = false)
		is PressurePlan -> copy(enabled = false)
		is WifiPlan -> copy(mode = WifiMode.OFF)
		is CellPlan -> copy(mode = CellMode.OFF)
	}

	private fun SourcePlan.acceptReduction(
		candidate: SourcePlan,
		demands: List<SourceDemand>,
	): SourcePlan {
		if (candidate == this) return this
		val bounded = if (this is LocationPlan && candidate is LocationPlan) {
			candidate.raisedToLocationFloor(this, demands)
		} else candidate
		return if (demands.all(bounded::satisfies)) bounded else this
	}

	private fun LocationPlan.raisedToLocationFloor(
		policyCeiling: LocationPlan,
		demands: List<SourceDemand>,
	): LocationPlan {
		val minimumMode = demands.mapNotNull { demand ->
			(demand.acquisitionFloor as? LocationAcquisitionFloor)?.minimumMode
		}.maxByOrNull(LocationFloorMode::level) ?: return this
		val currentLevel = when (mode) {
			LocationMode.DISABLED,
			LocationMode.PROBE,
			-> return this
			LocationMode.PASSIVE -> LocationFloorMode.PASSIVE.level
			LocationMode.LOW_POWER -> LocationFloorMode.LOW_POWER.level
			LocationMode.BALANCED -> LocationFloorMode.BALANCED.level
			LocationMode.HIGH_ACCURACY -> LocationFloorMode.HIGH_ACCURACY.level
		}
		val boundedMode = if (currentLevel >= minimumMode.level) mode else when (minimumMode) {
			LocationFloorMode.PASSIVE -> LocationMode.PASSIVE
			LocationFloorMode.LOW_POWER -> LocationMode.LOW_POWER
			LocationFloorMode.BALANCED -> LocationMode.BALANCED
			LocationFloorMode.HIGH_ACCURACY -> LocationMode.HIGH_ACCURACY
		}
		val maximumRequestedDelayMs = demands.minOfOrNull { demand ->
			minOf(demand.maximumAgeMs, demand.requestedDeliveryLatencyMs ?: Long.MAX_VALUE)
		} ?: Long.MAX_VALUE
		// Motion adaptation may lengthen cadence/batching, but never beyond a direct demand and
		// never to a more expensive cadence than the immutable policy ceiling originally allowed.
		val boundedRequestedIntervalMs = requestedIntervalMs
			.coerceAtMost(maximumRequestedDelayMs)
			.coerceAtLeast(policyCeiling.requestedIntervalMs)
		val boundedBatchDelayMs = maximumBatchDelayMs
			.coerceAtMost(maximumRequestedDelayMs)
			.coerceAtLeast(policyCeiling.maximumBatchDelayMs)
		val boundedMinimumIntervalMs = minimumUpdateIntervalMs
			.coerceAtMost(boundedRequestedIntervalMs)
			.coerceAtLeast(policyCeiling.minimumUpdateIntervalMs)
		return copy(
			mode = boundedMode,
			requestedIntervalMs = boundedRequestedIntervalMs,
			minimumUpdateIntervalMs = boundedMinimumIntervalMs,
			maximumBatchDelayMs = boundedBatchDelayMs,
		)
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
			// Pressure has no implemented movement-triggered burst contract. A direct Pressure demand
			// therefore keeps its honest continuous plan instead of depending on Activity to wake it.
			is PressurePlan -> this
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

	private fun LocationPlan.adaptiveAllDay(profile: CollectionAcquisitionProfile, level: Int?): LocationPlan {
		if (!enabled || level == null) return this
		if (profile.stationary) {
			return adaptiveAllDayStationary(level)
		}
		// Unknown motion keeps useful high-accuracy fixes; absence of a fix never implies stillness.
		return if (!profile.moving && level == BALANCED_LOCATION_LEVEL) {
			copy(requestedIntervalMs = maxOf(requestedIntervalMs, 30_000L),
				minimumUpdateIntervalMs = maxOf(minimumUpdateIntervalMs, 15_000L),
				maximumBatchDelayMs = maxOf(maximumBatchDelayMs, 60_000L))
		} else this
	}

	private fun LocationPlan.adaptiveAllDayStationary(level: Int) = when (level) {
		1 -> adaptiveStationary(intervalMs = 120_000L, minimumIntervalMs = 60_000L, batchDelayMs = 240_000L)
		2 -> adaptiveStationary(intervalMs = 90_000L, minimumIntervalMs = 45_000L, batchDelayMs = 180_000L)
		// Balanced and higher retain Wi-Fi's existing 30-second Location bracketing cadence.
		else -> adaptiveStationary()
	}

	private fun LocationPlan.adaptiveStationary(
		intervalMs: Long = 30_000L,
		minimumIntervalMs: Long = 15_000L,
		batchDelayMs: Long = 60_000L,
	) = copy(
		mode = if (mode == LocationMode.HIGH_ACCURACY) LocationMode.BALANCED else mode,
		requestedIntervalMs = maxOf(requestedIntervalMs, intervalMs),
		minimumUpdateIntervalMs = maxOf(minimumUpdateIntervalMs, minimumIntervalMs),
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = maxOf(maximumBatchDelayMs, batchDelayMs),
		probeDurationMs = null,
	)

	private fun PressurePlan.minimumUsefulContinuousBatched() = copy(
		enabled = true,
		hardwareSamplePeriodMicros = maxOf(hardwareSamplePeriodMicros, 1_000_000),
		maximumReportLatencyMicros = maxOf(maximumReportLatencyMicros, 60_000_000),
		aggregationWindowMs = maxOf(aggregationWindowMs, 60_000L),
	)

	private fun SourceDegradedReason.isHardPrerequisite(): Boolean = when (this) {
		SourceDegradedReason.HARDWARE_UNAVAILABLE,
		SourceDegradedReason.PROVIDER_UNAVAILABLE,
		SourceDegradedReason.PERMISSION_MISSING,
		SourceDegradedReason.FOREGROUND_CAPABILITY_MISSING,
		SourceDegradedReason.BACKGROUND_START_ILLEGAL,
		-> true
		else -> false
	}
}
