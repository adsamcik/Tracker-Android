package com.adsamcik.tracker.stats.api.repository

/**
 * Read-only, source-qualified Steps totals for decision-making product consumers.
 *
 * Implementations must compose the requested days from durable source facts and exact historical
 * authority. A complete day requires explicit covered facts to tile every exact Steps-authorized
 * segment/manifest slice intersecting that day; neither a baseline nor a terminal drain fills a
 * temporal gap. Reading this facade must never start a provider, repair a projection, or write a
 * derived summary. Only [StepsNumericSummary.Ready] is safe for goals, awards, streaks,
 * achievements, widgets, or notifications.
 */
interface StepsNumericSummaryRepository {
	/** Reads one coherent durable snapshot for [request]. */
	suspend fun read(request: StepsNumericSummaryRequest): StepsNumericSummary
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
	/** No overlapping session captured Steps with exact durable authority. */
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
