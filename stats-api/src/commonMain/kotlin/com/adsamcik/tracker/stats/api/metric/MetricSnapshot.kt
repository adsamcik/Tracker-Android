package com.adsamcik.tracker.stats.api.metric

class MetricSnapshot private constructor(private val values: Map<MetricKey, Double>) {
	val changedMetricCandidates: Set<MetricKey> get() = values.keys
	fun valueOf(metric: MetricKey): Double = values[metric] ?: 0.0
	fun asMap(): Map<MetricKey, Double> = values
	companion object {
		val Empty = MetricSnapshot(emptyMap())
		fun from(values: Map<MetricKey, Double>): MetricSnapshot = if (values.isEmpty()) Empty else MetricSnapshot(values)
		fun of(vararg values: Pair<MetricKey, Number>): MetricSnapshot = from(values.associate { (metric, value) -> metric to value.toDouble() })
	}
}
