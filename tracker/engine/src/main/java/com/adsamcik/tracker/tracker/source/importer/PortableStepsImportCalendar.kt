package com.adsamcik.tracker.tracker.source.importer

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Resolves the persisted calendar authority shared by portable admission and source-local deletion.
 * Callers must already hold every [PreparedPortableStepsDayScope.lockDays] lock and a Room transaction.
 */
internal suspend fun AppDatabase.resolvePortableStepsRepairZones(
	prepared: PreparedPortableStepsDayScope,
): Map<Long, ZoneId> {
	val persistedWindows = readPersistedImportCalendarWindows(prepared.lockDays)
	val candidateWindowsByDay = prepared.candidateZonesByDay.mapValues { (epochDay, zones) ->
		zones.map { zoneId -> zoneId to dayWindow(epochDay, zoneId) }
	}
	validateImportCalendarConstraints(persistedWindows, candidateWindowsByDay, prepared.coverageIntervals)
	val resolved = persistedWindows.filterValues { (_, window) ->
		prepared.coverageIntervals.any(window::overlaps)
	}.mapValuesTo(linkedMapOf()) { (_, authority) -> authority.first }
	for ((epochDay, candidateZones) in candidateWindowsByDay.toSortedMap()) {
		resolved.resolveImportCalendarDay(epochDay, candidateZones, persistedWindows, prepared.coverageIntervals)
	}
	resolved.validateImportCalendarCoverage(prepared.coverageIntervals)
	return resolved.toSortedMap()
}

private suspend fun AppDatabase.readPersistedImportCalendarWindows(
	lockDays: List<Long>,
): Map<Long, Pair<ZoneId, ImportWallInterval>> {
	val lockedDaySet = lockDays.toSet()
	val summaries = dailySummaryDao().getBetween(lockDays.first(), lockDays.last())
		.filter { summary -> summary.dateEpochDay in lockedDaySet }
	if (summaries.map(DailySummaryEntity::dateEpochDay).distinct().size != summaries.size) {
		throw dayRepairUnverifiable()
	}
	val persistedWindows = linkedMapOf<Long, Pair<ZoneId, ImportWallInterval>>()
	for (summary in summaries) {
		val zoneId = summary.importCalendarZone()
		persistedWindows[summary.dateEpochDay] = zoneId to dayWindow(summary.dateEpochDay, zoneId)
	}
	return persistedWindows
}

private fun DailySummaryEntity.importCalendarZone(): ZoneId = try {
	ZoneId.of(calendarZoneId ?: throw dayRepairUnverifiable())
} catch (_: DateTimeException) {
	throw dayRepairUnverifiable()
}

private fun validateImportCalendarConstraints(
	persistedWindows: Map<Long, Pair<ZoneId, ImportWallInterval>>,
	candidateWindowsByDay: Map<Long, List<Pair<ZoneId, ImportWallInterval>>>,
	coverageIntervals: List<ImportWallInterval>,
) {
	val constraintWindows = persistedWindows.filter { (epochDay, authority) ->
		epochDay in candidateWindowsByDay || coverageIntervals.any(authority.second::overlaps)
	}.values.map { (_, window) -> window }.sortedBy(ImportWallInterval::startMs)
	if (constraintWindows.zipWithNext().any { (left, right) -> right.startMs < left.endMs }) {
		throw dayRepairUnverifiable()
	}
}

private fun MutableMap<Long, ZoneId>.resolveImportCalendarDay(
	epochDay: Long,
	candidateZones: List<Pair<ZoneId, ImportWallInterval>>,
	persistedWindows: Map<Long, Pair<ZoneId, ImportWallInterval>>,
	coverageIntervals: List<ImportWallInterval>,
) {
	val candidateSlices = candidateZones.flatMap { (_, window) ->
		coverageIntervals.mapNotNull(window::intersection)
	}.distinct()
	if (candidateSlices.isEmpty()) {
		throw dayRepairUnverifiable()
	}
	val resolvedWindows = map { (day, zone) -> dayWindow(day, zone) }.sortedBy(ImportWallInterval::startMs)
	if (candidateSlices.all(resolvedWindows::cover)) {
		return
	}
	if (epochDay in persistedWindows) {
		throw dayRepairUnverifiable()
	}
	this[epochDay] = candidateZones.selectImportCalendarZone(persistedWindows.values, resolvedWindows, candidateSlices)
}

private fun List<Pair<ZoneId, ImportWallInterval>>.selectImportCalendarZone(
	persistedWindows: Collection<Pair<ZoneId, ImportWallInterval>>,
	resolvedWindows: List<ImportWallInterval>,
	candidateSlices: List<ImportWallInterval>,
): ZoneId {
	val viableByWindow = filterNot { (_, candidateWindow) ->
		persistedWindows.any { (_, persistedWindow) -> persistedWindow.overlaps(candidateWindow) } ||
			resolvedWindows.any(candidateWindow::overlaps)
	}.groupBy { (_, window) -> window }.filterKeys { candidateWindow ->
		val coverageWindows = (resolvedWindows + candidateWindow).sortedBy(ImportWallInterval::startMs)
		candidateSlices.all(coverageWindows::cover)
	}
	return viableByWindow.values.singleOrNull()?.minByOrNull { (zone, _) -> zone.id }?.first
		?: throw dayRepairUnverifiable()
}

private fun Map<Long, ZoneId>.validateImportCalendarCoverage(coverageIntervals: List<ImportWallInterval>) {
	if (isEmpty() || size > StepsNumericSummaryRequest.MAX_DAY_COUNT) {
		throw dayRepairUnverifiable()
	}
	val windows = map { (epochDay, zoneId) -> dayWindow(epochDay, zoneId) }.sortedBy(ImportWallInterval::startMs)
	if (windows.zipWithNext().any { (left, right) -> right.startMs < left.endMs } ||
		coverageIntervals.any { interval -> !windows.cover(interval) }
	) {
		throw dayRepairUnverifiable()
	}
}

internal fun PortableStepsEntryV1.prepareForImport(): PreparedPortableStepsDayScope {
	val entry = snapshotAndValidate()
	val candidateZonesByDay = linkedMapOf<Long, MutableSet<ZoneId>>()
	val lockDays = sortedSetOf<Long>()
	val coverageIntervals = mutableListOf<ImportWallInterval>()
	var defaultZoneId: ZoneId? = null
	for (run in entry.runs) {
		val zoneId = run.importCalendarZone()
		if (defaultZoneId == null) {
			defaultZoneId = zoneId
		}
		includeImportInterval(run.startTimeMs, run.endTimeMs, zoneId, candidateZonesByDay, lockDays, coverageIntervals)
		for (fact in run.facts) {
			includeImportInterval(
				fact.intervalStartTimeMs, fact.importRepairEndTimeMs(), zoneId,
				candidateZonesByDay, lockDays, coverageIntervals,
			)
		}
	}
	candidateZonesByDay.keys.validateImportDaySpan()
	return PreparedPortableStepsDayScope(
		entry = entry,
		candidateZonesByDay = candidateZonesByDay.mapValues { (_, zones) -> zones.toSet() },
		lockDays = lockDays.toList(),
		defaultZoneId = requireNotNull(defaultZoneId),
		coverageIntervals = coverageIntervals,
	)
}

private fun includeImportInterval(
	startMs: Long,
	endMs: Long,
	zoneId: ZoneId,
	candidateZonesByDay: MutableMap<Long, MutableSet<ZoneId>>,
	lockDays: MutableSet<Long>,
	coverageIntervals: MutableList<ImportWallInterval>,
) {
	val interval = ImportWallInterval(startMs, endMs)
	val exactDays = affectedEpochDays(startMs, endMs, zoneId, zoneId, MAX_IMPORT_DAYS)
	val plausibleDays = affectedEpochDays(startMs, endMs, ZoneOffset.MIN, ZoneOffset.MAX, MAX_IMPORT_LOCK_DAYS)
	for (epochDay in exactDays) {
		candidateZonesByDay.getOrPut(epochDay) { linkedSetOf() }.add(zoneId)
		val window = dayWindow(epochDay, zoneId)
		lockDays += affectedEpochDays(
			window.startMs, window.endMs, ZoneOffset.MIN, ZoneOffset.MAX, MAX_IMPORT_LOCK_DAYS,
		)
	}
	lockDays += exactDays
	lockDays += plausibleDays
	coverageIntervals += interval
	if (candidateZonesByDay.size > MAX_IMPORT_DAYS || lockDays.size > MAX_IMPORT_LOCK_DAYS) {
		throw dependencyOverflow()
	}
}

private fun PortableStepsRunV1.importCalendarZone(): ZoneId {
	if (endTimeMs <= startTimeMs) {
		throw PortableStepsImportUnverifiableException(PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE)
	}
	return try {
		ZoneId.of(storedZoneId)
	} catch (_: DateTimeException) {
		throw PortableStepsImportUnverifiableException(PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE)
	}
}

private fun PortableStepsFactV1.importRepairEndTimeMs(): Long = when {
	intervalEndTimeMs > intervalStartTimeMs -> intervalEndTimeMs
	stepCount != null || intervalStartTimeMs == Long.MAX_VALUE -> throw dayRepairUnverifiable()
	else -> intervalStartTimeMs + 1L
}

private fun Collection<Long>.validateImportDaySpan() {
	val firstDay = minOrNull()
	val lastDay = maxOrNull()
	if (firstDay == null || lastDay == null) {
		throw dayRepairUnverifiable()
	}
	val span = try {
		Math.addExact(Math.subtractExact(lastDay, firstDay), 1L)
	} catch (_: ArithmeticException) {
		null
	}
	if (span == null || span !in 1L..MAX_IMPORT_DAYS.toLong()) {
		throw dependencyOverflow()
	}
}

private fun affectedEpochDays(
	startTimeMs: Long,
	endTimeMs: Long,
	startZoneId: ZoneId,
	endZoneId: ZoneId,
	maximumDays: Int,
): List<Long> {
	val days = if (endTimeMs <= startTimeMs || maximumDays <= 0) {
		null
	} else {
		try {
			val lastCoveredMs = Math.subtractExact(endTimeMs, 1L)
			val startDay = Instant.ofEpochMilli(startTimeMs).atZone(startZoneId).toLocalDate().toEpochDay()
			val endDay = Instant.ofEpochMilli(lastCoveredMs).atZone(endZoneId).toLocalDate().toEpochDay()
			val dayCount = Math.addExact(Math.subtractExact(endDay, startDay), 1L)
			if (dayCount !in 1L..maximumDays.toLong()) {
				null
			} else {
				List(dayCount.toInt()) { index -> Math.addExact(startDay, index.toLong()) }
			}
		} catch (_: ArithmeticException) {
			null
		} catch (_: DateTimeException) {
			null
		}
	}
	return days ?: throw dependencyOverflow()
}

private fun dayWindow(epochDay: Long, zoneId: ZoneId): ImportWallInterval {
	val window = try {
		val startMs = LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()
		val endMs = LocalDate.ofEpochDay(Math.addExact(epochDay, 1L))
			.atStartOfDay(zoneId).toInstant().toEpochMilli()
		ImportWallInterval(startMs, endMs).takeIf { interval -> interval.endMs > interval.startMs }
	} catch (_: ArithmeticException) {
		null
	} catch (_: DateTimeException) {
		null
	}
	return window ?: throw dayRepairUnverifiable()
}

private fun List<ImportWallInterval>.cover(target: ImportWallInterval): Boolean {
	var cursor = target.startMs
	for (window in this) {
		if (window.endMs <= cursor) {
			continue
		}
		if (window.startMs > cursor) {
			return false
		}
		cursor = maxOf(cursor, window.endMs)
		if (cursor >= target.endMs) {
			return true
		}
	}
	return false
}

internal fun dayRepairUnverifiable() = PortableStepsImportUnverifiableException(
	PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE,
)

private fun dependencyOverflow() = PortableStepsImportUnverifiableException(
	PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW,
)

internal data class PreparedPortableStepsDayScope(
	val entry: PortableStepsEntryV1,
	val candidateZonesByDay: Map<Long, Set<ZoneId>>,
	val lockDays: List<Long>,
	val defaultZoneId: ZoneId,
	val coverageIntervals: List<ImportWallInterval>,
)

internal data class ImportWallInterval(
	val startMs: Long,
	val endMs: Long,
) {
	init {
		require(endMs > startMs)
	}

	fun overlaps(other: ImportWallInterval): Boolean = startMs < other.endMs && endMs > other.startMs

	fun intersection(other: ImportWallInterval): ImportWallInterval? {
		val start = maxOf(startMs, other.startMs)
		val end = minOf(endMs, other.endMs)
		return if (end > start) {
			ImportWallInterval(start, end)
		} else {
			null
		}
	}
}

internal class PortableStepsImportUnverifiableException(
	val reason: PortableStepsImportUnverifiableReason,
) : IllegalStateException()

private const val MAX_IMPORT_DAYS = StepsNumericSummaryRequest.MAX_DAY_COUNT
private const val MAX_IMPORT_LOCK_DAYS = MAX_IMPORT_DAYS + 2
