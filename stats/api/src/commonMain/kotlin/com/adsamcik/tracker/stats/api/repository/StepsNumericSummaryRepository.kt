package com.adsamcik.tracker.stats.api.repository

import kotlinx.coroutines.flow.Flow

/**
 * Read-only, source-qualified Steps totals and separately typed daily display values.
 *
 * Implementations must compose the requested days from durable source facts and exact historical
 * authority. A complete day is supplied either by one authoritative Ambient Steps day total or by
 * explicit covered facts for every exact session Steps slice intersecting that day. Session values
 * are never added to an Ambient total. Neither a baseline nor a terminal drain fills a temporal
 * gap. Reading this facade must never start a provider, repair a projection, or write a derived
 * summary. Only [StepsNumericSummary.Ready] is safe for goals, awards, streaks, achievements,
 * widgets, or notifications.
 */
interface StepsNumericSummaryRepository {
	/**
	 * Presentation-only per-day values from one bounded durable snapshot. A bad or unfinished day
	 * must not remove independently qualified numbers from another day. Recorded subtotals are
	 * never complete totals and must not be used by games, goals, awards, widgets or notifications.
	 */
	suspend fun readDisplay(request: StepsNumericSummaryRequest): StepsDailyDisplay

	/** Observes the same complete dependency set as strict numeric reads; never starts tracking. */
	fun observeDisplay(request: StepsNumericSummaryRequest): Flow<StepsDailyDisplay>

	/** Reads one coherent durable snapshot for [request]. */
	suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary

	/**
	 * Reads one or two demonstrated consumer windows from the same durable snapshot.
	 *
	 * The bounded batch exists for consumers such as daily plus week-to-date goal presentation,
	 * where combining independently committed snapshots could temporarily expose an impossible
	 * pair. Implementations must not turn this into per-day or per-source query fan-out.
	 */
	suspend fun readBatch(requests: List<StepsNumericSummaryRequest>): StepsNumericSummaryBatch

	/**
	 * Observes coherent snapshots for [request] while the caller is subscribed.
	 *
	 * Implementations must invalidate for every durable dependency that can change qualification,
	 * not only for the derived daily summary. This remains a read-only product operation and must
	 * never repair storage or acquire a provider demand.
	 */
	fun observe(request: StepsNumericSummaryRequest): Flow<StepsNumericSummary>

	/** Observes a transaction-coherent [readBatch] result while the caller is subscribed. */
	fun observeBatch(requests: List<StepsNumericSummaryRequest>): Flow<StepsNumericSummaryBatch>
}

/** One bounded, transaction-coherent set of qualified numeric results. */
data class StepsNumericSummaryBatch(
	val summaries: List<StepsNumericSummary>,
) {
	init {
		require(summaries.size in 1..MAX_SUMMARY_COUNT) {
			"Steps numeric summary batch requires one or two requested windows"
		}
	}

	/**
	 * Limits a coherent summary batch to the two demonstrated consumer windows.
	 */
	companion object {
		const val MAX_SUMMARY_COUNT = 2
	}
}

/** One bounded inclusive range of structural calendar days. */
data class StepsNumericSummaryRequest(
	val firstEpochDay: Long,
	val lastEpochDayInclusive: Long,
	/** Used only for a requested day that has no persisted materialization-zone authority yet. */
	val fallbackCalendarZoneId: String,
) {
	private val spanDays = lastEpochDayInclusive.toULong() - firstEpochDay.toULong()

	init {
		require(lastEpochDayInclusive >= firstEpochDay) {
			"Steps numeric summary range cannot end before it starts"
		}
		require(spanDays < MAX_DAY_COUNT.toULong()) {
			"Steps numeric summary range cannot exceed $MAX_DAY_COUNT days"
		}
		require(fallbackCalendarZoneId.isNotBlank()) {
			"Steps numeric summary requires fallback calendar-zone authority"
		}
	}

	val dayCount: Int
		get() = (spanDays + 1u).toInt()

	/** Public construction bounds for one coherent read generation. */
	companion object {
		const val MAX_DAY_COUNT = 370
	}
}

/** A complete source-qualified total, or a typed nonnumeric boundary. */
sealed interface StepsNumericSummary {
	/** Every requested day is settled and every Steps-authorized slice has exact covered union. */
	data class Ready(
		val days: List<StepsNumericDay>,
	) : StepsNumericSummary {
		init {
			require(days.isNotEmpty()) { "Ready Steps summary requires at least one day" }
			require(days.size <= StepsNumericSummaryRequest.MAX_DAY_COUNT) {
				"Ready Steps summary cannot exceed ${StepsNumericSummaryRequest.MAX_DAY_COUNT} days"
			}
			require(days.zipWithNext().all { (left, right) ->
				left.epochDay != Long.MAX_VALUE && right.epochDay == left.epochDay + 1L
			}) { "Ready Steps summary days must be contiguous and strictly increasing" }
		}

		val totalSteps: Long = days.fold(0L) { total, day ->
			require(day.steps <= Long.MAX_VALUE - total) {
				"Ready Steps summary total cannot overflow"
			}
			total + day.steps
		}
	}

	/** A required run, writer, or projection is still settling; no complete number is exposed. */
	data object Materializing : StepsNumericSummary

	/** Retained evidence cannot prove a complete total; [reason] is never interpreted as zero. */
	data class Unverifiable(
		val reason: StepsNumericUnverifiableReason,
	) : StepsNumericSummary
}

/** One complete source-qualified day within [StepsNumericSummary.Ready]. */
data class StepsNumericDay(
	val epochDay: Long,
	val steps: Long,
) {
	init {
		require(steps >= 0L) { "Steps numeric day cannot be negative" }
	}
}

/** Stable reasons a decision-making consumer must withhold a complete number. */
enum class StepsNumericUnverifiableReason {
	/** Neither Ambient nor overlapping session history has exact durable Steps authority. */
	NOT_CAPTURED,
	/** Some, but not every, overlapping captured session qualified Steps. */
	PARTIAL_CAPTURE,
	/** Retained run, manifest, writer, fact, deletion, or completeness evidence is insufficient. */
	SOURCE_EVIDENCE_UNAVAILABLE,
	/** The requested structural day cannot be bound to one valid calendar-zone authority. */
	CALENDAR_AUTHORITY_UNAVAILABLE,
	/** The coherent local database read failed. */
	STORAGE_UNAVAILABLE,
}

/** A display snapshot does not authorize a numeric product decision. */
sealed interface StepsDailyDisplay {
	/**
	 * A bounded contiguous display-day snapshot with independent values under one source revision.
	 */
	data class Snapshot(
		val sourceEvidenceRevision: Long,
		val days: List<StepsDailyDisplayDay>,
	) : StepsDailyDisplay {
		init {
			require(sourceEvidenceRevision >= 0L)
			require(days.size in 1..StepsNumericSummaryRequest.MAX_DAY_COUNT)
			require(days.zipWithNext().all { (left, right) ->
				left.epochDay != Long.MAX_VALUE && right.epochDay == left.epochDay + 1L
			})
		}
	}

	/**
	 * The required saved source product is still settling, so a complete value is not available yet.
	 */
	data object Materializing : StepsDailyDisplay
	/**
	 * The saved-data snapshot could not be read; this is distinct from an empty or measured-zero
	 * result.
	 */
	data object StorageUnavailable : StepsDailyDisplay
}

/**
 * One local calendar epoch day with its independent display result, preserving healthy sibling
 * days.
 */
data class StepsDailyDisplayDay(
	val epochDay: Long,
	val value: StepsDailyDisplayValue,
)

/**
 * Display-only complete count, saved subtotal or unavailable state; incomplete values do not
 * authorize game effects.
 */
sealed interface StepsDailyDisplayValue {
	/**
	 * A complete nonnegative display count supported by saved day coverage.
	 */
	data class Complete(val count: Long) : StepsDailyDisplayValue {
		init { require(count >= 0L) }
	}

	/** Qualified saved subtotal; gaps and unfinished coverage retain their actual causes. */
	data class Recorded(
		val count: Long,
		val causes: Set<AmbientStepsHistoryCause>,
	) : StepsDailyDisplayValue {
		init {
			require(count >= 0L)
			require(causes.isNotEmpty())
		}
	}

	/**
	 * The required saved source product is still settling, so a complete value is not available yet.
	 */
	data object Materializing : StepsDailyDisplayValue
	/**
	 * This day has no display count; retains the numeric reason and any known source causes.
	 */
	data class Unavailable(
		val reason: StepsNumericUnverifiableReason,
		val causes: Set<AmbientStepsHistoryCause> = emptySet(),
	) : StepsDailyDisplayValue
}
