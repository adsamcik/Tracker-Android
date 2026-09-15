package com.adsamcik.tracker.stats.api.repository

/**
 * Exact retained Steps evidence for correction-safe product decisions.
 *
 * Implementations compose the complete retained product domain from qualified source facts. They
 * must not use `daily_summary` numeric values, start a provider, repair storage, or publish a
 * bounded prefix when paging or revision stability cannot be proven.
 */
interface StepsRetainedMetricsRepository {
	/** Reads one revision-stable lifetime total and best-day decision. */
	suspend fun readDecision(): StepsRetainedMetricsDecision
}

/** One coherent retained-domain decision, or unavailable local storage. */
sealed interface StepsRetainedMetricsDecision {
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val result: StepsRetainedMetrics,
		/** Stable lowercase SHA-256 over the retained result and its exact authority. */
		val sourceResultDigest: String,
	) : StepsRetainedMetricsDecision {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(SHA_256_HEX.matches(sourceResultDigest))
		}
	}

	data object StorageUnavailable : StepsRetainedMetricsDecision

	companion object {
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
	}
}

/** Complete retained Steps metrics, or a typed boundary that is never interpreted as zero. */
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

	/** At least one relevant run, writer, fact lane, or projection is still settling. */
	data object Materializing : StepsRetainedMetrics

	/** Retained evidence cannot prove a complete numeric result. */
	data class Unverifiable(
		val reason: StepsNumericUnverifiableReason,
	) : StepsRetainedMetrics
}
