package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/**
 * Exact provenance-bearing Steps snapshots for consumers that create reversible product effects.
 *
 * This is deliberately separate from [StepsNumericSummaryRepository]: presentation consumers do
 * not need persistence authority, while an effect writer must prove that the source revision it
 * evaluated is still current immediately before committing its derived decision. Reading remains
 * side-effect free and never acquires a provider demand.
 */
interface StepsNumericDecisionRepository {
	/** Reads one or two windows from one Room source-evidence snapshot. */
	suspend fun readDecisionBatch(
		requests: List<StepsNumericSummaryRequest>,
	): StepsNumericDecisionBatch

	/**
	 * Re-evaluates one or two historical windows under their previously persisted exact calendar
	 * authority. This is used only by correction repair; it must not reinterpret an old structural
	 * day through the process's current zone or a mutable daily-summary projection.
	 */
	suspend fun readExactDecisionBatch(
		requests: List<StepsNumericExactDecisionRequest>,
	): StepsNumericDecisionBatch

	/** Observes the same exact snapshot contract while a concrete product consumer is active. */
	fun observeDecisionBatch(
		requests: List<StepsNumericSummaryRequest>,
	): Flow<StepsNumericDecisionBatch>
}

/** One historical window plus the exact per-day zones under which its effect was decided. */
data class StepsNumericExactDecisionRequest(
	val request: StepsNumericSummaryRequest,
	val calendarAuthority: StepsNumericCalendarAuthority.Exact,
) {
	init {
		require(
			calendarAuthority.days.map(StepsNumericCalendarDay::epochDay) ==
				(request.firstEpochDay..request.lastEpochDayInclusive).toList(),
		) { "Exact historical Steps authority must cover the complete request" }
	}
}

/** A coherent source snapshot, or a transient failure that grants no effect-writing authority. */
sealed interface StepsNumericDecisionBatch {
	/**
	 * One source revision with one or two exact decision windows; numeric and unavailable results
	 * remain separately typed.
	 */
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val windows: List<StepsNumericDecisionWindow>,
	) : StepsNumericDecisionBatch {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(windows.size in 1..StepsNumericSummaryBatch.MAX_SUMMARY_COUNT)
		}
	}

	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : StepsNumericDecisionBatch
}

/** One result and its exact structural-calendar authority inside a decision snapshot. */
data class StepsNumericDecisionWindow(
	val request: StepsNumericSummaryRequest,
	val summary: StepsNumericSummary,
	val calendarAuthority: StepsNumericCalendarAuthority,
	/** Stable lowercase SHA-256 over the request, resolved calendar authority, and result. */
	val sourceResultDigest: String,
) {
	init {
		require(SHA_256_HEX.matches(sourceResultDigest))
		when (calendarAuthority) {
			is StepsNumericCalendarAuthority.Exact -> require(
				calendarAuthority.days.map(StepsNumericCalendarDay::epochDay) ==
					(request.firstEpochDay..request.lastEpochDayInclusive).toList(),
			) { "Exact Steps calendar authority must cover the complete requested range" }
			StepsNumericCalendarAuthority.Unavailable -> require(
				summary is StepsNumericSummary.Unverifiable,
			) { "Unavailable calendar authority must remain a typed nonnumeric result" }
		}
		if (summary is StepsNumericSummary.Ready) {
			require(summary.days.map(StepsNumericDay::epochDay) ==
				(request.firstEpochDay..request.lastEpochDayInclusive).toList()) {
				"Ready decision result must cover the complete requested range"
			}
		}
	}

	/**
	 * Canonical digest pattern for one exact Steps decision result.
	 */
	companion object {
		private val SHA_256_HEX = Regex("[0-9a-f]{64}")
	}
}

/** Exact persisted-or-fallback zone authority, or an explicit inability to resolve it. */
sealed interface StepsNumericCalendarAuthority {
	/**
	 * Contiguous increasing calendar days with the exact zones used for the original numeric
	 * decision.
	 */
	data class Exact(
		val days: List<StepsNumericCalendarDay>,
	) : StepsNumericCalendarAuthority {
		init {
			require(days.isNotEmpty())
			require(days.zipWithNext().all { (left, right) ->
				left.epochDay != Long.MAX_VALUE && right.epochDay == left.epochDay + 1L
			}) { "Steps calendar authority must be contiguous and strictly increasing" }
		}

		/** Canonical durable representation used by the bounded Steps goal-effect ledger. */
		val canonical: String = days.joinToString("\n") { day ->
			"${day.epochDay}=${day.zoneId}"
		}
	}

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data object Unavailable : StepsNumericCalendarAuthority
}

/** One structural day and the exact ZoneId used to interpret its source evidence. */
data class StepsNumericCalendarDay(
	val epochDay: Long,
	val zoneId: String,
) {
	init {
		require(zoneId.isNotBlank())
		require('\n' !in zoneId && '\r' !in zoneId)
	}
}
