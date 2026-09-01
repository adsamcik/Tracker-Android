package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDegradedReason
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint

/**
 * Exact parameters supplied to [android.hardware.SensorManager] for one Pressure registration.
 *
 * The semantic plan is a policy ceiling. The provider request additionally accounts for the
 * pressure sensor's advertised sampling bounds and whether it exposes a FIFO. This prevents
 * distinct labels from producing redundant provider replacements when Android receives the same
 * request tuple. [degradedReasons] keeps any unrealized demand visible; no provider request or
 * battery saving is claimed beyond the platform capabilities reported here.
 */
internal data class PressureProviderRequest(
	val samplePeriodMicros: Int,
	val maximumReportLatencyMicros: Int,
	val batchingEnabled: Boolean,
	val physicalConfigurationFingerprint: String,
	val degradedReasons: Set<SourceDegradedReason>,
)

internal fun PressurePlan.toPressureProviderRequest(
	sensorMinimumDelayMicros: Int,
	sensorMaximumDelayMicros: Int = Int.MAX_VALUE,
	fifoMaxEventCount: Int,
): PressureProviderRequest {
	val requestedSamplePeriodMicros = hardwareSamplePeriodMicros.coerceAtLeast(1)
	val sampleBounds = pressureSampleBounds(sensorMinimumDelayMicros, sensorMaximumDelayMicros)
	val effectiveSamplePeriodMicros = sampleBounds.clamp(requestedSamplePeriodMicros)
	val requestedReportLatencyMicros = maximumReportLatencyMicros.coerceAtLeast(0)
	val batchingAvailable = fifoMaxEventCount > 0
	val effectiveReportLatencyMicros = requestedReportLatencyMicros.takeIf { batchingAvailable } ?: 0
	val degradedReasons = pressureProviderDegradedReasons(
		requestedSamplePeriodMicros,
		requestedReportLatencyMicros,
		sampleBounds,
		batchingAvailable,
	)
	val effectivePlan = copy(
		hardwareSamplePeriodMicros = effectiveSamplePeriodMicros,
		maximumReportLatencyMicros = effectiveReportLatencyMicros,
	)
	return PressureProviderRequest(
		samplePeriodMicros = effectiveSamplePeriodMicros,
		maximumReportLatencyMicros = effectiveReportLatencyMicros,
		batchingEnabled = batchingAvailable && effectiveReportLatencyMicros > 0,
		physicalConfigurationFingerprint = effectivePlan.physicalConfigurationFingerprint(),
		degradedReasons = degradedReasons,
	)
}

private data class PressureSampleBounds(
	val minimumDelayMicros: Int,
	val maximumDelayMicros: Int?,
	val inconsistent: Boolean,
) {
	fun clamp(requestedSamplePeriodMicros: Int): Int = requestedSamplePeriodMicros
		.coerceAtLeast(minimumDelayMicros)
		.let { samplePeriod -> maximumDelayMicros?.let(samplePeriod::coerceAtMost) ?: samplePeriod }
}

private fun pressureSampleBounds(
	sensorMinimumDelayMicros: Int,
	sensorMaximumDelayMicros: Int,
): PressureSampleBounds {
	val minimumDelayMicros = sensorMinimumDelayMicros.takeIf { it > 0 } ?: 1
	val inconsistent = sensorMinimumDelayMicros > 0 &&
		sensorMaximumDelayMicros > 0 &&
		sensorMaximumDelayMicros < sensorMinimumDelayMicros
	val maximumDelayMicros = sensorMaximumDelayMicros.takeIf {
		it >= minimumDelayMicros && !inconsistent
	}
	return PressureSampleBounds(minimumDelayMicros, maximumDelayMicros, inconsistent)
}

private fun pressureProviderDegradedReasons(
	requestedSamplePeriodMicros: Int,
	requestedReportLatencyMicros: Int,
	sampleBounds: PressureSampleBounds,
	batchingAvailable: Boolean,
): Set<SourceDegradedReason> = buildSet {
	if (requestedSamplePeriodMicros < sampleBounds.minimumDelayMicros) {
		add(SourceDegradedReason.DEMAND_FLOOR_UNSATISFIED)
	}
	if (sampleBounds.inconsistent ||
		sampleBounds.maximumDelayMicros?.let { requestedSamplePeriodMicros > it } == true
	) {
		// The existing vocabulary has no cadence-ceiling reason. PROVIDER_UNAVAILABLE means
		// this otherwise usable provider cannot realize the requested low-power cadence.
		add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
	}
	if (requestedReportLatencyMicros > 0 && !batchingAvailable) {
		add(SourceDegradedReason.PROVIDER_UNAVAILABLE)
	}
}
