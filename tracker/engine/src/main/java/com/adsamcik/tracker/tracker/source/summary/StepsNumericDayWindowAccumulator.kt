package com.adsamcik.tracker.tracker.source.summary

import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bounded day-window accumulator for one coherent Steps composition generation.
 *
 * Metadata may describe many settled runs, but covered facts are consumed in service-run order.
 * Only the current run's coverage cursors and one cell per requested day are retained.
 */
internal class StepsNumericDayWindowAccumulator private constructor(
	private val windows: List<DayWindow>,
) {
	private val days = Array(windows.size) { DayCell() }
	private val overlapOrder = windows.indices.sortedBy { index -> windows[index].startMs }
	private val overlapPrefixMaxEnd = LongArray(overlapOrder.size).also { prefix ->
		var maximum = Long.MIN_VALUE
		for (orderedIndex in overlapOrder.indices) {
			maximum = maxOf(maximum, windows[overlapOrder[orderedIndex]].endMs)
			prefix[orderedIndex] = maximum
		}
	}
	private var currentRun: CurrentRun? = null
	private var compositionInvalid = false

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	fun addLogicalGroup(contributions: List<StepsNumericRunContribution>): Boolean {
		if (currentRun != null || contributions.isEmpty()) {
			return false
		}
		val logicalTrackingId = contributions.first().logicalTrackingId
		val logicalStartedAtMs = contributions.first().logicalStartedAtMs
		if (logicalTrackingId.isBlank() || contributions.any { contribution ->
				contribution.logicalTrackingId != logicalTrackingId ||
					contribution.logicalStartedAtMs != logicalStartedAtMs
			}
		) {
			return false
		}
		val overlaps = BooleanArray(windows.size)
		for (contribution in contributions) {
			val segmentDuration = contribution.segmentEndMs - contribution.segmentStartMs
			if (contribution.serviceRunId.isBlank() || segmentDuration <= 0L ||
				!contribution.distanceM.isFinite() || contribution.distanceM < 0f ||
				contribution.stepsManifestRevisions.any { revision -> revision <= 0L } ||
				contribution.stepsManifestRevisions.isEmpty() &&
				!contribution.hasManifestWithoutStepsCapture
			) {
				return false
			}
			forEachOverlappingWindow(contribution.segmentStartMs, contribution.segmentEndMs) { dayIndex ->
				val window = windows[dayIndex]
				val inDayDuration = minOf(contribution.segmentEndMs, window.endMs) -
					maxOf(contribution.segmentStartMs, window.startMs)
				if (inDayDuration <= 0L) {
					return false
				}
				val cell = days[dayIndex]
				cell.distanceM += contribution.distanceM.toDouble() *
					(inDayDuration.toDouble() / segmentDuration.toDouble())
				val accumulatedDuration = addExact(cell.durationMs, inDayDuration)
				if (accumulatedDuration == null) {
					compositionInvalid = true
				} else {
					cell.durationMs = accumulatedDuration
				}
				cell.hasContribution = true
				overlaps[dayIndex] = true
				if (contribution.stepsManifestRevisions.isNotEmpty()) {
					// A bound presentation interval is enough to avoid claiming NotCaptured, but
					// only qualified fact wall intervals may promote the day to exact coverage.
					cell.potentialStepsRunIds += contribution.serviceRunId
				}
				if (contribution.hasManifestWithoutStepsCapture) {
					cell.hasNonStepsCapture = true
				}
			}
		}
		for (dayIndex in windows.indices) {
			if (overlaps[dayIndex]) {
				markLogicalTrip(dayIndex, logicalTrackingId, logicalStartedAtMs)
			}
		}
		return true
	}

	fun addUnboundNonStepsCapture(startMs: Long, endMs: Long): Boolean {
		if (currentRun != null || endMs <= startMs) {
			return false
		}
		forEachOverlappingWindow(startMs, endMs) { dayIndex ->
			days[dayIndex].hasNonStepsCapture = true
		}
		return true
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	fun startRun(contribution: StepsNumericRunContribution): Boolean {
		if (currentRun != null || contribution.serviceRunId.isBlank() ||
			contribution.logicalTrackingId.isBlank() ||
			contribution.stepsManifestRevisions.isEmpty() ||
			contribution.stepsManifestRevisions.any { revision -> revision <= 0L }
		) {
			return false
		}
		currentRun = CurrentRun(
			serviceRunId = contribution.serviceRunId,
			logicalTrackingId = contribution.logicalTrackingId,
			logicalStartedAtMs = contribution.logicalStartedAtMs,
			capturedZoneId = contribution.capturedZoneId,
			stepsManifestRevisions = contribution.stepsManifestRevisions,
			hasManifestWithoutStepsCapture = contribution.hasManifestWithoutStepsCapture,
			coverageByDay = arrayOfNulls(windows.size),
		)
		return true
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	fun consumeCoveredFact(fact: StepsNumericCoveredFact): Boolean {
		val run = currentRun ?: return false
		if (fact.serviceRunId != run.serviceRunId || fact.endMs <= fact.startMs || fact.steps < 0L ||
			fact.wallTimeUncertaintyMs < 0L || fact.manifestRevision !in run.stepsManifestRevisions
		) {
			return false
		}
		val allocationsByZone = mutableMapOf<ZoneId, Map<Long, Long>>()
		forEachOverlappingWindow(fact.startMs, fact.endMs) { dayIndex ->
			val window = windows[dayIndex]
			val allocations = allocationsByZone[window.zoneId] ?: run {
				val resolved = allocateSteps(fact.startMs, fact.endMs, fact.steps, window.zoneId)
				if (resolved == null) {
					compositionInvalid = true
					emptyMap()
				} else {
					allocationsByZone[window.zoneId] = resolved
					resolved
				}
			}
			val allocated = allocations[window.epochDay] ?: 0L
			val accumulated = addExact(days[dayIndex].allocatedSteps, allocated)
			if (accumulated == null) {
				compositionInvalid = true
			} else {
				days[dayIndex].allocatedSteps = accumulated
			}
			val cell = days[dayIndex]
			cell.hasContribution = true
			if (run.hasManifestWithoutStepsCapture) {
				cell.hasNonStepsCapture = true
			}
			markLogicalTrip(dayIndex, run.logicalTrackingId, run.logicalStartedAtMs)
			val coverage = run.coverageByDay[dayIndex]
				?: RunDayCoverage(cell, run.serviceRunId).also { run.coverageByDay[dayIndex] = it }
			if (!coverage.consume(fact, window, run.capturedZoneId)) {
				return false
			}
		}
		return true
	}

	private fun markLogicalTrip(
		dayIndex: Int,
		logicalTrackingId: String,
		logicalStartedAtMs: Long,
	) {
		val window = windows[dayIndex]
		val cell = days[dayIndex]
		if (logicalStartedAtMs !in window.startMs until window.endMs ||
			!cell.countedLogicalTrips.add(logicalTrackingId)
		) {
			return
		}
		val accumulatedTrips = addExact(cell.tripCount, 1)
		if (accumulatedTrips == null) {
			compositionInvalid = true
		} else {
			cell.tripCount = accumulatedTrips
		}
	}

	fun finishRun(): Boolean {
		val run = currentRun ?: return false
		for (coverage in run.coverageByDay) {
			coverage?.finish()
		}
		currentRun = null
		return true
	}

	fun results(): List<StepsNumericAccumulatedDay>? {
		if (currentRun != null || compositionInvalid) {
			return null
		}
		return windows.mapIndexed { index, window ->
			val cell = days[index]
			if (!cell.distanceM.isFinite() || cell.distanceM > Float.MAX_VALUE ||
				cell.exactStepsOverflow ||
				cell.allocatedSteps !in 0L..Int.MAX_VALUE
			) {
				return null
			}
			val hasCompleteStepsCapture = cell.completeStepsRunIds.isNotEmpty()
			val hasPartialStepsCapture = cell.partialStepsRunIds.isNotEmpty() ||
				cell.potentialStepsRunIds.any { runId -> runId !in cell.completeStepsRunIds }
			StepsNumericAccumulatedDay(
				epochDay = window.epochDay,
				distanceM = cell.distanceM.toFloat(),
				steps = cell.allocatedSteps.toInt(),
				durationMs = cell.durationMs,
				tripCount = cell.tripCount,
				hasContribution = cell.hasContribution,
				exactSteps = cell.exactSteps,
				hasCompleteStepsCapture = hasCompleteStepsCapture,
				hasPartialStepsCapture = hasPartialStepsCapture,
				hasNonStepsCapture = cell.hasNonStepsCapture,
			)
		}
	}

	/** Invokes [block] only for precomputed structural windows intersecting the half-open interval. */
	private inline fun forEachOverlappingWindow(
		startMs: Long,
		endMs: Long,
		block: (dayIndex: Int) -> Unit,
	) {
		if (endMs <= startMs || overlapOrder.isEmpty()) {
			return
		}
		var low = 0
		var high = overlapOrder.size
		while (low < high) {
			val middle = (low + high) ushr 1
			if (overlapPrefixMaxEnd[middle] <= startMs) {
				low = middle + 1
			} else {
				high = middle
			}
		}
		var orderedIndex = low
		while (orderedIndex < overlapOrder.size) {
			val dayIndex = overlapOrder[orderedIndex]
			val window = windows[dayIndex]
			if (window.startMs >= endMs) {
				break
			}
			if (window.endMs > startMs) {
				block(dayIndex)
			}
			orderedIndex += 1
		}
	}

	/** Civil coverage derived only from the persisted wall interval of each qualified fact. */
	private class RunDayCoverage(
		private val day: DayCell,
		private val serviceRunId: String,
	) {
		private var cursor: Long? = null
		private var exactSteps = 0L
		private var partial = false

		@Suppress("CyclomaticComplexMethod", "ReturnCount")
		fun consume(
			fact: StepsNumericCoveredFact,
			window: DayWindow,
			capturedZoneId: ZoneId,
		): Boolean {
			val clippedStart = maxOf(fact.startMs, window.startMs)
			val clippedEnd = minOf(fact.endMs, window.endMs)
			if (clippedEnd <= clippedStart) {
				return false
			}
			if (cursor?.let { previousEnd -> clippedStart != previousEnd } == true ||
				!hasExactDayAuthority(fact, capturedZoneId, window.zoneId)
			) {
				partial = true
			}
			val crossesDayBoundary = fact.startMs < window.startMs || fact.endMs > window.endMs
			if (crossesDayBoundary && fact.steps > 0L) {
				partial = true
			} else if (!crossesDayBoundary) {
				exactSteps = addExact(exactSteps, fact.steps) ?: run {
					day.exactStepsOverflow = true
					exactSteps
				}
			}
			cursor = maxOf(cursor ?: clippedStart, clippedEnd)
			return true
		}

		fun finish() {
			if (cursor == null) {
				return
			}
			if (partial) {
				day.partialStepsRunIds += serviceRunId
			} else {
				day.completeStepsRunIds += serviceRunId
				day.exactSteps = addExact(day.exactSteps, exactSteps) ?: run {
					day.exactStepsOverflow = true
					day.exactSteps
				}
			}
		}
	}

	private data class CurrentRun(
		val serviceRunId: String,
		val logicalTrackingId: String,
		val logicalStartedAtMs: Long,
		val capturedZoneId: ZoneId,
		val stepsManifestRevisions: Set<Long>,
		val hasManifestWithoutStepsCapture: Boolean,
		val coverageByDay: Array<RunDayCoverage?>,
	)

	private data class DayWindow(
		val epochDay: Long,
		val zoneId: ZoneId,
		val startMs: Long,
		val endMs: Long,
	)

	private class DayCell {
		var distanceM = 0.0
		var allocatedSteps = 0L
		var durationMs = 0L
		var tripCount = 0
		var hasContribution = false
		var exactSteps = 0L
		var exactStepsOverflow = false
		var hasNonStepsCapture = false
		val potentialStepsRunIds = hashSetOf<String>()
		val completeStepsRunIds = hashSetOf<String>()
		val partialStepsRunIds = hashSetOf<String>()
		val countedLogicalTrips = hashSetOf<String>()
	}

	internal companion object Factory {
		@Suppress("CyclomaticComplexMethod", "ReturnCount")
		private fun allocateSteps(
			startMs: Long,
			endMs: Long,
			count: Long,
			zoneId: ZoneId,
		): Map<Long, Long>? {
			if (endMs <= startMs || count < 0L) {
				return null
			}
			val startDay = epochDay(startMs, zoneId) ?: return null
			val endDay = epochDay(endMs - 1L, zoneId) ?: return null
			val dayCount = endDay - startDay + 1L
			if (dayCount <= 0L || dayCount > StepsNumericSummaryRequest.MAX_DAY_COUNT) {
				return null
			}
			if (dayCount == 1L) {
				return mapOf(startDay to count)
			}
			val totalDuration = endMs - startMs
			val total = BigInteger.valueOf(totalDuration)
			val weighted = (0L until dayCount).map { offset ->
				val allocationDay = startDay + offset
				val dayEndMs = startOfDayMs(allocationDay + 1L, zoneId) ?: return null
				val dayStartMs = startOfDayMs(allocationDay, zoneId) ?: return null
				val duration = minOf(endMs, dayEndMs) - maxOf(startMs, dayStartMs)
				val numerator = BigInteger.valueOf(count).multiply(BigInteger.valueOf(duration))
				val division = numerator.divideAndRemainder(total)
				WeightedDay(allocationDay, division[0].toLong(), division[1])
			}.toMutableList()
			val assigned = weighted.sumOf(WeightedDay::base)
			val remainder = count - assigned
			if (remainder < 0L || remainder >= dayCount) {
				return null
			}
			weighted.sortWith(compareByDescending<WeightedDay> { it.remainder }.thenBy { it.epochDay })
			repeat(remainder.toInt()) { index ->
				weighted[index] = weighted[index].copy(base = weighted[index].base + 1L)
			}
			return weighted.associate { day -> day.epochDay to day.base }
		}

		private fun hasExactDayAuthority(
			fact: StepsNumericCoveredFact,
			capturedZoneId: ZoneId,
			summaryZoneId: ZoneId,
		): Boolean {
			val endpointsAreExact = hasExactEndpoints(fact, capturedZoneId) &&
				hasExactEndpoints(fact, summaryZoneId)
			val intervalIsExact = fact.steps == 0L || isContainedInCalendarDay(
				startMs = fact.startMs,
				endMs = fact.endMs,
				zoneId = capturedZoneId,
			)
			return endpointsAreExact && intervalIsExact
		}

		private fun hasExactEndpoints(fact: StepsNumericCoveredFact, zoneId: ZoneId): Boolean =
			hasExactEndpointDay(fact.startMs, fact.wallTimeUncertaintyMs, zoneId) &&
				hasExactEndpointDay(fact.endMs, fact.wallTimeUncertaintyMs, zoneId)

		private fun isContainedInCalendarDay(startMs: Long, endMs: Long, zoneId: ZoneId): Boolean = try {
			endMs > startMs && Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate() ==
				Instant.ofEpochMilli(Math.subtractExact(endMs, 1L)).atZone(zoneId).toLocalDate()
		} catch (_: DateTimeException) {
			false
		} catch (_: ArithmeticException) {
			false
		}

		private fun hasExactEndpointDay(wallTimeMs: Long, uncertaintyMs: Long, zoneId: ZoneId): Boolean {
			if (uncertaintyMs < 0L) {
				return false
			}
			val earliest = try {
				Math.subtractExact(wallTimeMs, uncertaintyMs)
			} catch (_: ArithmeticException) {
				return false
			}
			val latest = try {
				Math.addExact(wallTimeMs, uncertaintyMs)
			} catch (_: ArithmeticException) {
				return false
			}
			return try {
				val earliestInstant = Instant.ofEpochMilli(earliest)
				val latestInstant = Instant.ofEpochMilli(latest)
				epochDay(earliest, zoneId)?.let { earliestDay ->
					earliestDay == epochDay(latest, zoneId) &&
						zoneId.rules.getOffset(earliestInstant) == zoneId.rules.getOffset(latestInstant)
				} == true
			} catch (_: DateTimeException) {
				false
			}
		}

		private fun epochDay(instantMs: Long, zoneId: ZoneId): Long? = try {
			Instant.ofEpochMilli(instantMs).atZone(zoneId).toLocalDate().toEpochDay()
		} catch (_: DateTimeException) {
			null
		}

		private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long? = try {
			LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()
		} catch (_: DateTimeException) {
			null
		} catch (_: ArithmeticException) {
			null
		}

		private fun addExact(left: Long, right: Long): Long? = try {
			Math.addExact(left, right)
		} catch (_: ArithmeticException) {
			null
		}

		private fun addExact(left: Int, right: Int): Int? = try {
			Math.addExact(left, right)
		} catch (_: ArithmeticException) {
			null
		}
		@Suppress("ReturnCount")
		fun create(zoneByDay: Map<Long, ZoneId>): StepsNumericDayWindowAccumulator? {
			val ordered = zoneByDay.toSortedMap()
			if (ordered.isEmpty() || ordered.size > StepsNumericSummaryRequest.MAX_DAY_COUNT) {
				return null
			}
			val windows = ordered.map { (epochDay, zoneId) ->
				val nextEpochDay = try {
					Math.addExact(epochDay, 1L)
				} catch (_: ArithmeticException) {
					return null
				}
				val startMs = startOfDayMs(epochDay, zoneId) ?: return null
				val endMs = startOfDayMs(nextEpochDay, zoneId) ?: return null
				if (endMs <= startMs) {
					return null
				}
				DayWindow(epochDay, zoneId, startMs, endMs)
			}
			return StepsNumericDayWindowAccumulator(windows)
		}

		/**
		 * Creates a zero-window accumulator for selected-session deletion validation only.
		 *
		 * The ordinary factory deliberately rejects an empty product-day request. Deletion can have
		 * no persisted daily summary to repair while still needing to validate every surviving run
		 * and streamed fact before deciding whether deletion is safe or still materializing.
		 */
		fun createEmptyValidationOnly(): StepsNumericDayWindowAccumulator =
			StepsNumericDayWindowAccumulator(emptyList())
	}
}

internal data class StepsNumericRunContribution(
	val serviceRunId: String,
	val logicalTrackingId: String,
	val logicalStartedAtMs: Long,
	val capturedZoneId: ZoneId,
	val segmentStartMs: Long,
	val segmentEndMs: Long,
	val distanceM: Float,
	/** Manifest membership is monotonic-clock authority; it intentionally carries no wall bounds. */
	val stepsManifestRevisions: Set<Long>,
	/** Any manifest slice lacking Steps makes fact-backed civil totals conservative for this run. */
	val hasManifestWithoutStepsCapture: Boolean,
)

internal data class StepsNumericCoveredFact(
	val serviceRunId: String,
	val manifestRevision: Long,
	val startMs: Long,
	val endMs: Long,
	val steps: Long,
	val wallTimeUncertaintyMs: Long,
)

internal data class StepsNumericAccumulatedDay(
	val epochDay: Long,
	val distanceM: Float,
	val steps: Int,
	val durationMs: Long,
	val tripCount: Int,
	val hasContribution: Boolean,
	val exactSteps: Long,
	val hasCompleteStepsCapture: Boolean,
	val hasPartialStepsCapture: Boolean,
	val hasNonStepsCapture: Boolean,
)

private data class WeightedDay(
	val epochDay: Long,
	val base: Long,
	val remainder: BigInteger,
)
