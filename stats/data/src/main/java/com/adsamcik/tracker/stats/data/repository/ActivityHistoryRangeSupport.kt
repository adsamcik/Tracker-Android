package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableActivityEntryV1
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedZoneRange
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductRevisionSnapshot
import com.adsamcik.tracker.shared.base.database.dao.ActivityProductRevisionSnapshot
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeContinuation
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryStructuralDayCompleteness
import java.time.Instant
import java.time.ZoneId

internal data class ActivityHistorySnapshotRevision(
	val evidenceRevision: Long,
	val collectedDataEpoch: Long,
	val local: ActivityProductRevisionSnapshot,
	val imported: ImportedActivityProductRevisionSnapshot,
) {
	init {
		require(evidenceRevision >= 0L && collectedDataEpoch >= 0L)
	}
}

internal data class ActivityLocalRangeCursor(
	val recencyStartTimeMs: Long,
	val recencySegmentId: Long,
)

internal data class ActivityImportedRangeCursor(
	val recencyStartTimeMs: Long,
	val recencyMemberIdentity: String,
)

internal class ActivityHistoryRangeContinuationSnapshot(
	val issuer: Any,
	val scope: ActivityHistoryRangeScope,
	val revision: ActivityHistorySnapshotRevision,
	val localCursor: ActivityLocalRangeCursor?,
	val importedCursor: ActivityImportedRangeCursor?,
) : ActivityHistoryRangeContinuation

internal data class ActivityHistoryQueryBounds(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
)

internal fun ActivityHistoryRangeScope.queryBounds(): ActivityHistoryQueryBounds = when (this) {
	is ActivityHistoryRangeScope.WallTime ->
		ActivityHistoryQueryBounds(fromInclusive.raw, toExclusive.raw)
	is ActivityHistoryRangeScope.StructuralDays -> {
		val firstUtcStart = Math.multiplyExact(firstEpochDay, MILLIS_PER_DAY)
		val lastUtcEnd = Math.multiplyExact(Math.addExact(lastEpochDayInclusive, 1L), MILLIS_PER_DAY)
		ActivityHistoryQueryBounds(
			fromInclusiveMs = Math.subtractExact(firstUtcStart, MAX_ZONE_OFFSET_MS).coerceAtLeast(0L),
			toExclusiveMs = Math.addExact(lastUtcEnd, MAX_ZONE_OFFSET_MS),
		)
	}
}

internal data class ActivityTemporalAuthority(
	val ranges: List<ActivityTemporalZoneRange>,
	val complete: Boolean,
) {
	init {
		require(ranges == ranges.sortedWith(ACTIVITY_TEMPORAL_RANGE_ORDER))
		require(ranges.size <= MAX_ACTIVITY_TEMPORAL_RANGES)
	}
}

internal data class ActivityTemporalZoneRange(
	val startTimeMs: Long,
	val endInclusiveMs: Long,
	val storedZoneId: String,
) {
	init {
		require(startTimeMs >= 0L && endInclusiveMs >= startTimeMs)
		require(storedZoneId.isNotBlank())
		ZoneId.of(storedZoneId)
	}
}

internal fun localActivityTemporalAuthority(
	composed: ComposedActivityEntry,
	snapshot: ActivityHistorySnapshot,
): ActivityTemporalAuthority {
	val segments = snapshot.expansion.segments
		.filter { it.logicalTrackingId == composed.logicalTrackingId }
	if (segments.isEmpty()) throw ActivityHistoryRangeFailure()
	var complete = true
	val ranges = segments.flatMap { segment ->
		val runId = segment.serviceRunId ?: throw ActivityHistoryRangeFailure()
		val manifests = snapshot.manifestsByRun[runId].orEmpty()
			.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (manifests.isEmpty()) throw ActivityHistoryRangeFailure()
		val authority = temporalRanges(
			startTimeMs = segment.startTimeMs,
			endTimeMs = segment.endTimeMs,
			epochs = manifests.map { it.effectiveWallTimeMs to it.zoneId },
		)
		complete = complete && authority.complete
		authority.ranges
	}.sortedWith(ACTIVITY_TEMPORAL_RANGE_ORDER)
	if (ranges.size > MAX_ACTIVITY_TEMPORAL_RANGES) throw ActivityHistoryRangeLimitExceeded()
	return ActivityTemporalAuthority(ranges, complete)
}

internal fun importedActivityTemporalAuthority(
	evaluation: ImportedActivityProductEvaluation,
): ActivityTemporalAuthority? = when (evaluation) {
	is ImportedActivityProductEvaluation.Readable -> evaluation.entry.toTemporalAuthority()
	is ImportedActivityProductEvaluation.Retained -> ActivityTemporalAuthority(
		ranges = evaluation.structuralZoneRanges.map { it.toTemporalRange() }
			.sortedWith(ACTIVITY_TEMPORAL_RANGE_ORDER),
		complete = evaluation.structuralZoneCoverageComplete,
	)
	is ImportedActivityProductEvaluation.Unverifiable -> null
}

private fun PortableActivityEntryV1.toTemporalAuthority(): ActivityTemporalAuthority {
	var complete = true
	val ranges = runs.flatMap { run ->
		val authority = temporalRanges(
			startTimeMs = run.startTimeMs,
			endTimeMs = run.endTimeMs,
			epochs = run.zoneEpochs.map { it.effectiveWallTimeMs to it.zoneId },
		)
		complete = complete && authority.complete
		authority.ranges
	}.sortedWith(ACTIVITY_TEMPORAL_RANGE_ORDER)
	if (ranges.size > MAX_ACTIVITY_TEMPORAL_RANGES) throw ActivityHistoryRangeLimitExceeded()
	return ActivityTemporalAuthority(ranges, complete)
}

private fun ImportedActivityRetainedZoneRange.toTemporalRange() = ActivityTemporalZoneRange(
	startTimeMs,
	endInclusiveMs,
	storedZoneId,
)

private fun temporalRanges(
	startTimeMs: Long,
	endTimeMs: Long,
	epochs: List<Pair<Long, String>>,
): ActivityTemporalAuthority {
	require(startTimeMs >= 0L && endTimeMs >= startTimeMs)
	require(epochs.isNotEmpty())
	val ordered = epochs.sortedWith(compareBy<Pair<Long, String>>({ it.first }, { it.second }))
	require(ordered == epochs && ordered.map { it.first }.distinct().size == ordered.size)
	val endInclusive = if (endTimeMs > startTimeMs) endTimeMs - 1L else endTimeMs
	val complete = ordered.any { it.first <= startTimeMs }
	val ranges = ordered.mapIndexedNotNull { index, epoch ->
		val start = maxOf(startTimeMs, epoch.first)
		val next = ordered.getOrNull(index + 1)?.first
		val end = minOf(endInclusive, next?.let { if (it == 0L) -1L else it - 1L } ?: endInclusive)
		if (end < start) null else ActivityTemporalZoneRange(start, end, epoch.second)
	}
	if (ranges.isEmpty()) throw ActivityHistoryRangeFailure()
	return ActivityTemporalAuthority(ranges, complete)
}

internal fun activityHistoryRangeEntry(
	entry: com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry,
	authority: ActivityTemporalAuthority?,
	scope: ActivityHistoryRangeScope,
): ActivityHistoryRangeEntry? {
	if (authority == null) {
		return if (scope is ActivityHistoryRangeScope.WallTime &&
			entry.startTime.raw < scope.toExclusive.raw &&
			entry.endTime.raw > scope.fromInclusive.raw
		) {
			ActivityHistoryRangeEntry(
				entry,
				emptySet(),
				ActivityHistoryStructuralDayCompleteness.UNAVAILABLE,
			)
		} else {
			null
		}
	}
	val days = linkedSetOf<ActivityHistoryStructuralDay>()
	authority.ranges.forEach { range ->
		val clipped = range.clip(scope) ?: return@forEach
		val zone = ZoneId.of(clipped.storedZoneId)
		val firstDay = Instant.ofEpochMilli(clipped.startTimeMs).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(clipped.endInclusiveMs).atZone(zone).toLocalDate().toEpochDay()
		val boundedFirst = when (scope) {
			is ActivityHistoryRangeScope.WallTime -> firstDay
			is ActivityHistoryRangeScope.StructuralDays -> maxOf(firstDay, scope.firstEpochDay)
		}
		val boundedLast = when (scope) {
			is ActivityHistoryRangeScope.WallTime -> lastDay
			is ActivityHistoryRangeScope.StructuralDays -> minOf(lastDay, scope.lastEpochDayInclusive)
		}
		if (boundedLast < boundedFirst) return@forEach
		val span = Math.addExact(Math.subtractExact(boundedLast, boundedFirst), 1L)
		if (span > MAX_ACTIVITY_STRUCTURAL_DAYS_PER_ENTRY) {
			throw ActivityHistoryRangeLimitExceeded()
		}
		for (day in boundedFirst..boundedLast) {
			days += ActivityHistoryStructuralDay(day, clipped.storedZoneId)
		}
	}
	if (days.isEmpty()) return null
	return ActivityHistoryRangeEntry(
		entry = entry,
		structuralDays = days,
		structuralDayCompleteness = if (authority.complete) {
			ActivityHistoryStructuralDayCompleteness.EXACT
		} else {
			ActivityHistoryStructuralDayCompleteness.PARTIAL
		},
	)
}

private fun ActivityTemporalZoneRange.clip(
	scope: ActivityHistoryRangeScope,
): ActivityTemporalZoneRange? = when (scope) {
	is ActivityHistoryRangeScope.WallTime -> {
		val endLimit = scope.toExclusive.raw - 1L
		val start = maxOf(startTimeMs, scope.fromInclusive.raw)
		val end = minOf(endInclusiveMs, endLimit)
		if (end < start) null else copy(startTimeMs = start, endInclusiveMs = end)
	}
	is ActivityHistoryRangeScope.StructuralDays -> this
}

internal class ActivityHistoryRangeFailure : RuntimeException(null, null, false, false)

internal class ActivityHistoryRangeLimitExceeded : RuntimeException(null, null, false, false)

private val ACTIVITY_TEMPORAL_RANGE_ORDER =
	compareBy(ActivityTemporalZoneRange::startTimeMs)
		.thenBy(ActivityTemporalZoneRange::endInclusiveMs)
		.thenBy(ActivityTemporalZoneRange::storedZoneId)

private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MS = 18L * 60L * 60L * 1_000L
private const val MAX_ACTIVITY_TEMPORAL_RANGES = 16_384
private const val MAX_ACTIVITY_STRUCTURAL_DAYS_PER_ENTRY = 400L
