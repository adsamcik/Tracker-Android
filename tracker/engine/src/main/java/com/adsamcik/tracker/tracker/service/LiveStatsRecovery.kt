package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class LiveStatsRecoverySeed(
	val priorDayDistanceM: Float,
	val priorDaySteps: Int,
	val priorDayDurationMs: Long,
	val priorDayTrips: Int,
	val restoredDayDistanceM: Float,
	val restoredDaySteps: Int,
	val restoredDayDurationMs: Long,
)

/** Builds the in-memory stats seed from persisted session ground truth. */
internal suspend fun AppDatabase.liveStatsRecoverySeed(
	session: TrackerSession,
	isResuming: Boolean,
	zoneId: ZoneId = ZoneId.systemDefault(),
): LiveStatsRecoverySeed {
	val referenceMs = session.end.coerceAtLeast(session.start)
	val epochDay = Instant.ofEpochMilli(referenceMs).atZone(zoneId).toLocalDate().toEpochDay()
	val dayStartMs = startOfDayMs(epochDay, zoneId)
	val dayEndMs = startOfDayMs(epochDay + 1L, zoneId)
	val priorSegments = sessionSegmentDao().getOverlapping(dayStartMs, dayEndMs)
		.filterNot { segment -> segment.id == session.id }
	var priorDistance = 0f
	var priorSteps = 0
	var priorDuration = 0L
	var priorTrips = 0
	priorSegments.forEach { segment ->
		val contribution = contributionForDay(
			startMs = segment.startTimeMs,
			endMs = segment.endTimeMs,
			distanceM = segment.distanceM,
			steps = segment.steps ?: 0,
			dayStartMs = dayStartMs,
			dayEndMs = dayEndMs,
		)
		priorDistance += contribution.distanceM
		priorSteps += contribution.steps
		priorDuration += contribution.durationMs
		if (segment.startTimeMs in dayStartMs until dayEndMs) priorTrips++
	}

	val restored = if (isResuming) {
		contributionForDay(
			startMs = session.start,
			endMs = referenceMs,
			distanceM = session.distanceInM,
			steps = session.steps,
			dayStartMs = dayStartMs,
			dayEndMs = dayEndMs,
		)
	} else {
		DayContribution()
	}
	return LiveStatsRecoverySeed(
		priorDayDistanceM = priorDistance,
		priorDaySteps = priorSteps,
		priorDayDurationMs = priorDuration,
		priorDayTrips = priorTrips,
		restoredDayDistanceM = restored.distanceM,
		restoredDaySteps = restored.steps,
		restoredDayDurationMs = restored.durationMs,
	)
}

private data class DayContribution(
	val distanceM: Float = 0f,
	val steps: Int = 0,
	val durationMs: Long = 0L,
)

private fun contributionForDay(
	startMs: Long,
	endMs: Long,
	distanceM: Float,
	steps: Int,
	dayStartMs: Long,
	dayEndMs: Long,
): DayContribution {
	val durationMs = endMs - startMs
	if (durationMs <= 0L) return DayContribution()
	val inDayDuration = (minOf(endMs, dayEndMs) - maxOf(startMs, dayStartMs)).coerceAtLeast(0L)
	if (inDayDuration == 0L) return DayContribution()
	val fraction = inDayDuration.toDouble() / durationMs.toDouble()
	return DayContribution(
		distanceM = (distanceM * fraction).toFloat(),
		steps = (steps * fraction).toInt(),
		durationMs = inDayDuration,
	)
}

private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long = LocalDate.ofEpochDay(epochDay)
	.atStartOfDay(zoneId)
	.toInstant()
	.toEpochMilli()
