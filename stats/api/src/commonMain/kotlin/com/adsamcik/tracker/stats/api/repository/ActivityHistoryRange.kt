package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Source range selected either by absolute wall time or by retained stored-zone day authority. */
sealed interface ActivityHistoryRangeScope {
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

data class ActivityHistoryRangeRequest(
	val scope: ActivityHistoryRangeScope,
	val limit: Int,
	val continuation: ActivityHistoryRangeContinuation? = null,
) {
	init {
		require(limit in 1..MAX_PAGE_SIZE)
	}

	companion object {
		const val MAX_PAGE_SIZE = 100
	}
}

data class ActivityHistoryStructuralDay(
	val epochDay: Long,
	val storedZoneId: String,
) {
	init {
		require(storedZoneId.isNotBlank())
	}
}

enum class ActivityHistoryStructuralDayCompleteness {
	EXACT,
	PARTIAL,
	UNAVAILABLE,
}

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

sealed interface ActivityHistoryRangePage {
	data class Available(
		val entries: List<ActivityHistoryRangeEntry>,
		val continuation: ActivityHistoryRangeContinuation?,
	) : ActivityHistoryRangePage {
		init {
			require(entries.size <= ActivityHistoryRangeRequest.MAX_PAGE_SIZE)
		}
	}

	data class Unavailable(
		val reason: ActivityHistoryRangeUnavailableReason,
	) : ActivityHistoryRangePage

	data class Failed(
		val cause: ActivityHistoryCause,
	) : ActivityHistoryRangePage {
		init {
			require(cause.isIntegrityFailure)
		}
	}
}

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
