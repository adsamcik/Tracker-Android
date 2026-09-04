package com.adsamcik.tracker.stats.api.repository

/**
 * Read-only Steps metrics over the complete retained product domain.
 *
 * Implementations must compose these values from authenticated source facts and exact historical
 * calendar authority. They must not read a derived numeric projection as source proof, start a
 * provider, or repair storage. Only [StepsRetainedMetrics.Ready] is safe for achievements and
 * other lifetime numeric decisions.
 */
interface StepsRetainedMetricsRepository {
	/** Reads one coherent durable generation without loading the complete fact store into memory. */
	suspend fun read(): StepsRetainedMetrics
}

/** Complete retained Steps metrics, or a typed nonnumeric boundary. */
sealed interface StepsRetainedMetrics {
	/** At least one retained day has complete, source-qualified Steps coverage. */
	data class Ready(
		val totalSteps: Long,
		val bestDailySteps: Long,
		val qualifiedDayCount: Long,
	) : StepsRetainedMetrics {
		init {
			require(totalSteps >= 0L) { "Retained Steps total cannot be negative" }
			require(bestDailySteps >= 0L) { "Retained best-day Steps cannot be negative" }
			require(qualifiedDayCount > 0L) { "Ready retained Steps require a qualified day" }
			require(bestDailySteps <= totalSteps) {
				"Retained best-day Steps cannot exceed the lifetime total"
			}
		}
	}

	/** At least one relevant run, writer, or projection is still settling. */
	data object Materializing : StepsRetainedMetrics

	/** Retained evidence cannot prove a complete number; this must never be interpreted as zero. */
	data class Unverifiable(
		val reason: StepsNumericUnverifiableReason,
	) : StepsRetainedMetrics
}
