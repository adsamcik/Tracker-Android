package com.adsamcik.tracker.tracker.source.battery

import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject

interface BatteryImpactEstimator {
	fun estimate(plan: AcquisitionPlanRevision, comparisonBaselineId: String?): BatteryImpactEstimate
}

class QualitativeBatteryImpactEstimator @Inject constructor() : BatteryImpactEstimator {
	override fun estimate(
		plan: AcquisitionPlanRevision,
		comparisonBaselineId: String?,
	): BatteryImpactEstimate {
		var score = FIXED_TRACKING_SCORE
		val drivers = mutableMapOf<ImpactDriver, Int>()
		fun add(driver: ImpactDriver, cost: Int) {
			score += cost
			drivers[driver] = drivers.getOrDefault(driver, 0) + cost
		}

		(plan.plans[SourceKind.LOCATION] as? LocationPlan)?.let { location ->
			add(
				ImpactDriver.LOCATION,
				when (location.mode) {
					LocationMode.DISABLED -> 0
					LocationMode.PASSIVE -> 1
					LocationMode.LOW_POWER -> 2
					LocationMode.BALANCED -> 4
					LocationMode.HIGH_ACCURACY, LocationMode.PROBE -> 8
				},
			)
			if (location.maximumBatchDelayMs > location.requestedIntervalMs) score -= 1
		}
		(plan.plans[SourceKind.ACTIVITY] as? ActivityPlan)?.let { activity ->
			add(ImpactDriver.ACTIVITY, if (activity.mode == ActivityMode.CONTINUOUS_RECOGNITION) 3 else if (activity.enabled) 1 else 0)
		}
		(plan.plans[SourceKind.STEPS] as? StepsPlan)?.let { steps ->
			if (steps.enabled) add(ImpactDriver.STEPS, if (steps.movementPolicyNeedsLowLatency) 2 else 1)
		}
		(plan.plans[SourceKind.PRESSURE] as? PressurePlan)?.let { pressure ->
			if (pressure.enabled) {
				add(ImpactDriver.PRESSURE, if (pressure.hardwareSamplePeriodMicros <= 100_000) 4 else 2)
				if (pressure.maximumReportLatencyMicros > pressure.hardwareSamplePeriodMicros) score -= 1
			}
		}
		(plan.plans[SourceKind.WIFI] as? WifiPlan)?.let { wifi ->
			add(
				ImpactDriver.WIFI,
				when (wifi.mode) {
					WifiMode.OFF -> 0
					WifiMode.CACHED_ONLY,
					WifiMode.BROADCAST_DRIVEN,
					-> 1
					WifiMode.ACTIVE_ATTEMPTS -> 5
				},
			)
		}
		(plan.plans[SourceKind.CELL] as? CellPlan)?.let { cell ->
			add(ImpactDriver.CELL, if (cell.mode == CellMode.OBSERVE_AND_SPARSE_REFRESH) 3 else if (cell.enabled) 1 else 0)
		}

		val level = when {
			score <= 7 -> ImpactLevel.LOW
			score <= 14 -> ImpactLevel.MODERATE
			else -> ImpactLevel.HIGH
		}
		return BatteryImpactEstimate(
			level = level,
			estimatedPercentPerHour = null,
			estimateTarget = EstimateTarget.QUALITATIVE_RELATIVE_TRACKER_IMPACT,
			candidatePlanId = plan.planId,
			comparisonBaselineId = comparisonBaselineId,
			evidenceSource = EvidenceSource.GENERIC_PRIOR,
			sampleCount = 0,
			observationDurationMs = 0L,
			confidence = EstimateConfidence.LOW,
			uncertainty = null,
			dominantDrivers = drivers.entries
				.sortedByDescending(Map.Entry<ImpactDriver, Int>::value)
				.map(Map.Entry<ImpactDriver, Int>::key)
				.take(3),
			assumptions = listOf(
				ImpactAssumption("generic_device_prior"),
				ImpactAssumption("android_delivery_is_not_guaranteed"),
				ImpactAssumption("shared_wakeups_are_not_additive"),
			),
			calibrationVersion = 0,
		)
	}

	private companion object {
		const val FIXED_TRACKING_SCORE = 2
	}
}
