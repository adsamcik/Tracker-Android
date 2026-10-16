package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Source range selected either by absolute wall time or by retained stored-zone day authority. */
sealed interface ActivityHistoryRangeScope {
	/**
	 * Nonempty half-open UTC millisecond selection bounded to the supported history interval.
	 */
	data class WallTime(
		val fromInclusive: EpochMs,
		val toExclusive: EpochMs,
	) : ActivityHistoryRangeScope {
		init {
			require(fromInclusive.raw >= 0L)
			require(toExclusive > fromInclusive)
			require(toExclusive.raw - fromInclusive.raw <= MAX_ACTIVITY_RANGE_MILLIS)
		}
	}

	/**
	 * Inclusive bounded local calendar epoch-day range interpreted using retained saved zones.
	 */
	data class StructuralDays(
		val firstEpochDay: Long,
		val lastEpochDayInclusive: Long,
	) : ActivityHistoryRangeScope {
		init {
			require(firstEpochDay in MIN_ACTIVITY_EPOCH_DAY..MAX_ACTIVITY_EPOCH_DAY)
			require(lastEpochDayInclusive in firstEpochDay..MAX_ACTIVITY_EPOCH_DAY)
			require(lastEpochDayInclusive - firstEpochDay < MAX_ACTIVITY_RANGE_DAY_COUNT)
		}
	}
}

/** Opaque continuation. Only the exact repository instance that issued it may resume it. */
interface ActivityHistoryRangeContinuation

/**
 * A bounded Activity page request; a continuation resumes only the repository instance and scope
 * that issued it.
 */
data class ActivityHistoryRangeRequest(
	val scope: ActivityHistoryRangeScope,
	val limit: Int,
	val continuation: ActivityHistoryRangeContinuation? = null,
) {
	init {
		require(limit in 1..MAX_PAGE_SIZE)
	}

	/**
	 * Maximum number of logical Activity entries returned by one range page.
	 */
	companion object {
		const val MAX_PAGE_SIZE = 100
	}
}

/**
 * Saved local calendar day and zone associated with an Activity entry; the day is not a UTC
 * timestamp.
 */
data class ActivityHistoryStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(storedZoneId.isNotBlank())
	}
}

/**
 * Whether all, some or none of an entry's stored calendar-day membership can be identified.
 */
enum class ActivityHistoryStructuralDayCompleteness {
	EXACT,
	PARTIAL,
	UNAVAILABLE,
}

/**
 * One Activity entry plus its verified saved-day membership and the completeness of that
 * allocation.
 */
data class ActivityHistoryRangeEntry(
	val entry: ActivityHistoryEntry,
	val structuralDays: Set<ActivityHistoryStructuralDay>,
	val structuralDayCompleteness: ActivityHistoryStructuralDayCompleteness,
) {
	init {
		require(structuralDays.none { it.storedZoneId.isBlank() })
		when (structuralDayCompleteness) {
			ActivityHistoryStructuralDayCompleteness.EXACT,
			ActivityHistoryStructuralDayCompleteness.PARTIAL,
			-> require(structuralDays.isNotEmpty())
			ActivityHistoryStructuralDayCompleteness.UNAVAILABLE -> require(structuralDays.isEmpty())
		}
	}
}

/**
 * Bounded Activity range results, unavailable temporal/cursor authority or an explicit integrity
 * failure.
 */
sealed interface ActivityHistoryRangePage {
	/**
	 * Bounded saved entries and an optional source-issued continuation; null ends the range.
	 */
	data class Available(
		val entries: List<ActivityHistoryRangeEntry>,
		val continuation: ActivityHistoryRangeContinuation?,
	) : ActivityHistoryRangePage {
		init {
			require(entries.size <= ActivityHistoryRangeRequest.MAX_PAGE_SIZE)
		}
	}

	/**
	 * The requested data or authority is unavailable; any named reason preserves the original failure
	 * cause.
	 */
	data class Unavailable(
		val reason: ActivityHistoryRangeUnavailableReason,
	) : ActivityHistoryRangePage

	/**
	 * The stored source or scope failed integrity qualification; this outcome does not expose a
	 * measured count.
	 */
	data class Failed(
		val cause: ActivityHistoryCause,
	) : ActivityHistoryRangePage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

/**
 * Invalid continuation or unavailable temporal authority preventing the requested Activity page.
 */
enum class ActivityHistoryRangeUnavailableReason {
	INVALID_CONTINUATION,
	TEMPORAL_AUTHORITY_UNAVAILABLE,
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ACTIVITY_RANGE_DAY_COUNT = 370L
private const val MAX_ACTIVITY_RANGE_MILLIS =
	MAX_ACTIVITY_RANGE_DAY_COUNT * MILLIS_PER_DAY
private const val MIN_ACTIVITY_EPOCH_DAY = 0L
private const val MAX_ACTIVITY_EPOCH_DAY = Long.MAX_VALUE / MILLIS_PER_DAY - 2L
