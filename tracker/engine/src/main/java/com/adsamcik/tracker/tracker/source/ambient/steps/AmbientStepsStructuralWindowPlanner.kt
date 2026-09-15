package com.adsamcik.tracker.tracker.source.ambient.steps

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Immutable calendar authority for one civil day under one explicitly observed zone. */
internal data class AmbientStepsStructuralDay(
	val epochDay: Long,
	val zoneId: String,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(zoneId.isNotBlank())
		require(startTimeMs >= 0L)
		require(endTimeMs > startTimeMs)
		require(startTimeMs % MILLIS_PER_SECOND == 0L)
		require(endTimeMs % MILLIS_PER_SECOND == 0L)
		require(endTimeMs - startTimeMs <= MAX_PROVIDER_WINDOW_MILLIS) {
			"An Ambient Steps structural day exceeds the provider read-window contract"
		}
		val parsedZone = ZoneId.of(zoneId)
		val expectedDate = LocalDate.ofEpochDay(epochDay)
		require(expectedDate.atStartOfDay(parsedZone).toInstant().toEpochMilli() == startTimeMs)
		require(expectedDate.plusDays(1L).atStartOfDay(parsedZone).toInstant().toEpochMilli() == endTimeMs)
	}
}

/** One non-empty provider window that belongs to exactly one stored structural day. */
internal data class AmbientStepsStructuralWindow(
	val day: AmbientStepsStructuralDay,
	val startTimeMs: Long,
	val endTimeMs: Long,
) {
	init {
		require(startTimeMs >= day.startTimeMs)
		require(endTimeMs <= day.endTimeMs)
		require(endTimeMs > startTimeMs)
		require(startTimeMs % MILLIS_PER_SECOND == 0L)
		require(endTimeMs % MILLIS_PER_SECOND == 0L)
	}

	val providerWindow: AmbientStepsProviderReadWindow
		get() = AmbientStepsProviderReadWindow(startTimeMs, endTimeMs)

	val completesStructuralDay: Boolean
		get() = startTimeMs == day.startTimeMs && endTimeMs == day.endTimeMs
}

internal data class AmbientStepsStructuralWindowPlan(
	val windows: List<AmbientStepsStructuralWindow>,
	/** First unplanned instant when the bounded pass cannot cover the complete requested range. */
	val deferredFromTimeMs: Long?,
) {
	init {
		require(windows.zipWithNext().all { (previous, next) ->
			previous.endTimeMs == next.startTimeMs
		})
		require(deferredFromTimeMs == null || windows.lastOrNull()?.endTimeMs == deferredFromTimeMs)
	}
}

/**
 * Splits one explicitly attributed range at civil-day boundaries without creating a background
 * cadence. The caller owns zone authority: this planner must not be used to guess an unobserved
 * zone transition across process absence or reboot.
 */
internal class AmbientStepsStructuralWindowPlanner(
	private val maximumWindowsPerPass: Int = DEFAULT_MAXIMUM_WINDOWS_PER_PASS,
) {
	init {
		require(maximumWindowsPerPass in 1..MAXIMUM_WINDOWS_PER_PASS_LIMIT)
	}

	fun plan(
		fromTimeMs: Long,
		throughTimeMs: Long,
		zoneId: ZoneId,
	): AmbientStepsStructuralWindowPlan {
		require(fromTimeMs >= 0L)
		require(throughTimeMs >= fromTimeMs)
		require(fromTimeMs % MILLIS_PER_SECOND == 0L)
		require(throughTimeMs % MILLIS_PER_SECOND == 0L)
		if (fromTimeMs == throughTimeMs) {
			return AmbientStepsStructuralWindowPlan(emptyList(), deferredFromTimeMs = null)
		}

		val windows = ArrayList<AmbientStepsStructuralWindow>(maximumWindowsPerPass)
		var cursor = fromTimeMs
		while (cursor < throughTimeMs && windows.size < maximumWindowsPerPass) {
			val day = structuralDayAt(cursor, zoneId)
			val endTimeMs = minOf(day.endTimeMs, throughTimeMs)
			windows += AmbientStepsStructuralWindow(day, cursor, endTimeMs)
			cursor = endTimeMs
		}
		return AmbientStepsStructuralWindowPlan(
			windows = windows,
			deferredFromTimeMs = cursor.takeIf { it < throughTimeMs },
		)
	}

	/**
	 * Plans the one fact revision that owns the cursor high-water.
	 *
	 * While the cursor remains inside a civil day, later reads deliberately start at the same
	 * structural-day/continuity boundary so the provider aggregate revises one stable logical fact.
	 * At a completed day boundary the next advancing read starts the following day. An exact replay
	 * at the same high-water instead selects the preceding non-empty window.
	 */
	fun planProgressive(
		segmentStartTimeMs: Long,
		importedThroughTimeMs: Long,
		throughTimeMs: Long,
		zoneId: ZoneId,
	): AmbientStepsStructuralWindowPlan {
		require(segmentStartTimeMs >= 0L)
		require(importedThroughTimeMs >= segmentStartTimeMs)
		require(throughTimeMs >= importedThroughTimeMs)
		require(segmentStartTimeMs % MILLIS_PER_SECOND == 0L)
		require(importedThroughTimeMs % MILLIS_PER_SECOND == 0L)
		require(throughTimeMs % MILLIS_PER_SECOND == 0L)
		if (throughTimeMs == importedThroughTimeMs &&
			importedThroughTimeMs == segmentStartTimeMs
		) {
			return AmbientStepsStructuralWindowPlan(emptyList(), deferredFromTimeMs = null)
		}

		val dayLookupTimeMs = if (throughTimeMs == importedThroughTimeMs) {
			importedThroughTimeMs - MILLIS_PER_SECOND
		} else {
			importedThroughTimeMs
		}
		val day = structuralDayAt(dayLookupTimeMs, zoneId)
		val startTimeMs = maxOf(segmentStartTimeMs, day.startTimeMs)
		val endTimeMs = if (throughTimeMs == importedThroughTimeMs) {
			importedThroughTimeMs
		} else {
			minOf(day.endTimeMs, throughTimeMs)
		}
		val window = AmbientStepsStructuralWindow(day, startTimeMs, endTimeMs)
		return AmbientStepsStructuralWindowPlan(
			windows = listOf(window),
			deferredFromTimeMs = endTimeMs.takeIf { it < throughTimeMs },
		)
	}

	private fun structuralDayAt(timeMs: Long, zoneId: ZoneId): AmbientStepsStructuralDay {
		val date = Instant.ofEpochMilli(timeMs).atZone(zoneId).toLocalDate()
		return AmbientStepsStructuralDay(
			epochDay = date.toEpochDay(),
			zoneId = zoneId.id,
			startTimeMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
			endTimeMs = date.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli(),
		)
	}

	private companion object {
		const val DEFAULT_MAXIMUM_WINDOWS_PER_PASS = 10
		const val MAXIMUM_WINDOWS_PER_PASS_LIMIT = 31
	}
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_PROVIDER_WINDOW_MILLIS = 25L * 60L * 60L * MILLIS_PER_SECOND
