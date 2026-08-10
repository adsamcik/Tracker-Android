package com.adsamcik.tracker.tracker.source.battery

data class BatteryImpactEstimate(
	val level: ImpactLevel,
	val estimatedPercentPerHour: ClosedFloatingPointRange<Double>?,
	val estimateTarget: EstimateTarget,
	val candidatePlanId: String,
	val comparisonBaselineId: String?,
	val evidenceSource: EvidenceSource,
	val sampleCount: Int,
	val observationDurationMs: Long,
	val confidence: EstimateConfidence,
	val uncertainty: ClosedFloatingPointRange<Double>?,
	val dominantDrivers: List<ImpactDriver>,
	val assumptions: List<ImpactAssumption>,
	val calibrationVersion: Int,
) {
	init {
		require(candidatePlanId.isNotBlank())
		require(sampleCount >= 0)
		require(observationDurationMs >= 0L)
		require(calibrationVersion >= 0)
		require(
			estimateTarget != EstimateTarget.ESTIMATED_INCREMENTAL_TRACKER_DRAIN ||
				evidenceSource == EvidenceSource.VALIDATED_COUNTERFACTUAL,
		) { "Incremental Tracker drain requires validated counterfactual evidence" }
	}
}

enum class ImpactLevel { LOW, MODERATE, HIGH }
enum class EstimateTarget {
	QUALITATIVE_RELATIVE_TRACKER_IMPACT,
	OBSERVED_TOTAL_DEVICE_DRAIN,
	ESTIMATED_INCREMENTAL_TRACKER_DRAIN,
}

enum class EvidenceSource { GENERIC_PRIOR, DEVICE_FAMILY_MEASUREMENTS, ON_DEVICE_OBSERVATIONS, VALIDATED_COUNTERFACTUAL }
enum class EstimateConfidence { LOW, MEDIUM, HIGHER }
enum class ImpactDriver { FOREGROUND_RUNTIME, LOCATION, ACTIVITY, STEPS, PRESSURE, WIFI, CELL, WAKEUPS, DATABASE, CPU }

data class ImpactAssumption(val code: String) {
	init {
		require(code.isNotBlank())
	}
}

