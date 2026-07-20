package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.analysis.PresenceCompactor
import com.adsamcik.tracker.shared.base.database.analysis.MetricAnalysisGrid
import com.adsamcik.tracker.shared.base.database.dao.PresenceCellAggregateRow
import com.adsamcik.tracker.shared.base.database.dao.SupportBucketRow
import com.adsamcik.tracker.shared.base.database.data.AnalysisCell
import com.adsamcik.tracker.shared.base.database.data.PresenceInterval
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceBounds
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceCell
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceCoverage
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceCoverageState
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceRepository
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceRequest
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceSnapshot
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** Room-backed, uncertainty-aware projection of observation-supported presence. */
class DefaultObservedPresenceRepository @Inject constructor(
	private val database: AppDatabase,
) : ObservedPresenceRepository {
	private val compactor = PresenceCompactor(database)
	private val loadMutex = Mutex()

	override suspend fun load(request: ObservedPresenceRequest): ObservedPresenceSnapshot = loadMutex.withLock {
		val nowExclusive = (System.currentTimeMillis() + 1L).coerceAtLeast(1L)
		val fromMs = request.fromMs.coerceAtLeast(0L)
		val toMs = minOf(request.toMsExclusive, nowExclusive)
		val preferredResolution = normalizeResolution(request.preferredResolutionM)
		if (toMs <= fromMs) {
			return@withLock emptySnapshot(preferredResolution)
		}

		val materializationComplete = ensureMaterialized(fromMs, toMs, nowExclusive)
		val presenceDao = database.presenceIntervalDao()
		val coverageRow = presenceDao.getCoverage(PresenceCompactor.MODEL_KEY, fromMs, toMs)
		val supportHistogram = presenceDao.getSupportHistogram(PresenceCompactor.MODEL_KEY, fromMs, toMs)
		val resolvedMs = coverageRow.observedMs + coverageRow.inferredMs
		val uncertaintyFloor = uncertaintyResolutionFloor(supportHistogram, resolvedMs)
		val initialResolution = maxResolution(preferredResolution, uncertaintyFloor)

		val fullFromMs = ceilUtcDay(fromMs)
		val fullToMs = startOfUtcDay(toMs)
		val fragments = boundaryFragments(fromMs, toMs, fullFromMs, fullToMs)
		val boundaryPresence = loadBoundaryPresence(fragments)
		val bounds = request.bounds ?: WORLD_BOUNDS

		var chosenResolution = initialResolution
		var boundaryCells = aggregateBoundaryCells(boundaryPresence, chosenResolution, bounds)
		var estimatedCellCount = committedCellCount(
			resolutionM = chosenResolution,
			fromMs = fullFromMs,
			toMs = fullToMs,
			bounds = bounds,
		) + boundaryCells.size

		while (estimatedCellCount > request.maxCells) {
			val coarser = nextCoarserResolution(chosenResolution) ?: break
			chosenResolution = coarser
			boundaryCells = aggregateBoundaryCells(boundaryPresence, chosenResolution, bounds)
			estimatedCellCount = committedCellCount(
				resolutionM = chosenResolution,
				fromMs = fullFromMs,
				toMs = fullToMs,
				bounds = bounds,
			) + boundaryCells.size
		}

		val merged = LinkedHashMap<String, MutableCellMass>()
		loadCommittedCells(chosenResolution, fullFromMs, fullToMs, bounds).forEach { row ->
			merged.getOrPut(row.cellId) { MutableCellMass(row) }.add(row)
		}
		boundaryCells.forEach { (cellId, mass) ->
			merged.getOrPut(cellId) { mass.copyWithoutMass() }.add(mass)
		}

		val orderedCells = merged.values
			.asSequence()
			.filter { it.expectedMs > 0L }
			.sortedWith(compareByDescending<MutableCellMass> { it.expectedMs }.thenBy { it.cellId })
			.toList()
		val budgeted = applyPresenceFeatureBudget(
			ordered = orderedCells,
			maxFeatures = request.maxCells,
			expectedMs = MutableCellMass::expectedMs,
		)
		val cells = budgeted.included.map(MutableCellMass::toApi)
		val omittedPresentationMs = budgeted.omittedMs
		val supportedMs = supportHistogram
			.filter { it.resolutionM in 1..chosenResolution }
			.sumOf(SupportBucketRow::supportMs)

		ObservedPresenceSnapshot(
			cells = cells,
			resolutionM = chosenResolution,
			isCellBudgetTruncated = budgeted.truncated,
			metricKind = PresenceCompactor.METRIC_KIND,
			modelKey = PresenceCompactor.MODEL_KEY,
			estimatorVersion = PresenceCompactor.ESTIMATOR_VERSION,
			calibrationVersion = PresenceCompactor.CALIBRATION_VERSION,
			gridVersion = MetricAnalysisGrid.GRID_VERSION,
			coverage = ObservedPresenceCoverage(
				state = coverageState(
					materializationComplete = materializationComplete,
					totalMs = coverageRow.totalMs,
					unresolvedMs = coverageRow.unresolvedMs,
					resolvedMs = resolvedMs,
					supportedMs = supportedMs,
					omittedPresentationMs = omittedPresentationMs,
				),
				trackedSeconds = coverageRow.totalMs / MILLIS_PER_SECOND,
				spatiallyObservedSeconds = coverageRow.observedMs / MILLIS_PER_SECOND,
				spatiallyInferredSeconds = coverageRow.inferredMs / MILLIS_PER_SECOND,
				spatiallyUnresolvedSeconds = coverageRow.unresolvedMs / MILLIS_PER_SECOND,
				supportedAtDisplayResolutionSeconds = supportedMs / MILLIS_PER_SECOND,
				omittedPresentationSeconds = omittedPresentationMs / MILLIS_PER_SECOND,
			),
		)
	}

	/**
	 * Materializes short, interactive ranges exactly. For a large all-time backfill the compactor
	 * advances a bounded number of oldest partitions and advertises MATERIALIZING until caught up.
	 */
	private suspend fun ensureMaterialized(fromMs: Long, toMs: Long, nowExclusive: Long): Boolean {
		val firstDay = startOfUtcDay(fromMs)
		val lastDay = startOfUtcDay(toMs - 1L)
		val dayCount = ((lastDay - firstDay) / UTC_DAY_MS) + 1L
		val presenceAnalysisDao = database.presenceAnalysisDao()
		if (dayCount <= MAX_DIRECT_PARTITIONS) {
			var day = firstDay
			while (day <= lastDay) {
				val dayEnd = day + UTC_DAY_MS
				if (dayEnd <= nowExclusive) {
					if (!presenceAnalysisDao.hasCommittedBlock(
							PresenceCompactor.METRIC_KIND,
							PresenceCompactor.MODEL_KEY,
							MetricAnalysisGrid.GRID_VERSION,
							day,
						)
					) {
						compactor.compactPartition(day, advanceCheckpoint = false)
					}
				} else if (day < nowExclusive) {
					compactor.materializePresence(day, nowExclusive)
				}
				day = dayEnd
			}
			return true
		}

		val progress = compactor.compactThroughExclusive(
			requestedThroughMs = startOfUtcDay(toMs),
			maxPartitions = MAX_PROGRESSIVE_PARTITIONS,
		)
		materializeBoundaryDayIfNeeded(firstDay, fromMs != firstDay, nowExclusive)
		materializeBoundaryDayIfNeeded(lastDay, toMs != lastDay + UTC_DAY_MS, nowExclusive)
		return progress.complete
	}

	private suspend fun materializeBoundaryDayIfNeeded(
		day: Long,
		isBoundaryFragment: Boolean,
		nowExclusive: Long,
	) {
		if (!isBoundaryFragment || day >= nowExclusive) return
		val dayEnd = day + UTC_DAY_MS
		val presenceAnalysisDao = database.presenceAnalysisDao()
		if (dayEnd <= nowExclusive) {
			if (!presenceAnalysisDao.hasCommittedBlock(
					PresenceCompactor.METRIC_KIND,
					PresenceCompactor.MODEL_KEY,
					MetricAnalysisGrid.GRID_VERSION,
					day,
				)
			) {
				compactor.compactPartition(day, advanceCheckpoint = false)
			}
		} else {
			compactor.materializePresence(day, nowExclusive)
		}
	}

	private suspend fun loadBoundaryPresence(windows: List<TimeWindow>): List<ClippedPresence> {
		val dao = database.presenceIntervalDao()
		return windows.flatMap { window ->
			dao.getOverlapping(PresenceCompactor.MODEL_KEY, window.fromMs, window.toMs).mapNotNull { row ->
				val clippedFrom = maxOf(row.startTimeMs, window.fromMs)
				val clippedTo = minOf(row.endTimeMs, window.toMs)
				if (clippedTo > clippedFrom) {
					ClippedPresence(row, clippedTo - clippedFrom)
				} else {
					null
				}
			}
		}
	}

	private fun aggregateBoundaryCells(
		presence: List<ClippedPresence>,
		resolutionM: Int,
		bounds: ObservedPresenceBounds,
	): LinkedHashMap<String, MutableCellMass> {
		val result = LinkedHashMap<String, MutableCellMass>()
		presence.forEach { clipped ->
			val row = clipped.row
			if (row.resolutionState == STATE_UNRESOLVED) return@forEach
			val latE7 = row.centerLatE7 ?: return@forEach
			val lonE7 = row.centerLonE7 ?: return@forEach
			val r90M = row.effectiveR90M ?: return@forEach
			val finest = MetricAnalysisGrid.finestSupportedResolution(r90M) ?: return@forEach
			if (resolutionM < finest) return@forEach
			val allocated = MetricAnalysisGrid.allocateMillis(
				clipped.millis,
				MetricAnalysisGrid.weightedCells(
				latDeg = latE7 / 1e7,
				lonDeg = lonE7 / 1e7,
				effectiveR90M = r90M,
				resolutionM = resolutionM,
				),
			)
			allocated.forEach cellLoop@{ weighted ->
				if (!weighted.cell.intersects(bounds)) return@cellLoop
				val mass = result.getOrPut(weighted.cell.cellId) { MutableCellMass(weighted.cell) }
				mass.expectedMs += weighted.millis
				if (row.resolutionState == STATE_OBSERVED) {
					mass.observedMs += weighted.millis
				} else {
					mass.inferredMs += weighted.millis
				}
			}
		}
		return result
	}

	private suspend fun committedCellCount(
		resolutionM: Int,
		fromMs: Long,
		toMs: Long,
		bounds: ObservedPresenceBounds,
	): Int {
		if (toMs <= fromMs) return 0
		val dao = database.presenceAnalysisDao()
		return bounds.longitudeWindowsE7().sumOf { longitude ->
			dao.countViewportCells(
				metricKind = PresenceCompactor.METRIC_KIND,
				modelKey = PresenceCompactor.MODEL_KEY,
				gridVersion = MetricAnalysisGrid.GRID_VERSION,
				resolutionM = resolutionM,
				fromMs = fromMs,
				toMs = toMs,
				southE7 = bounds.south.toE7(),
				northE7 = bounds.north.toE7(),
				westE7 = longitude.first,
				eastE7 = longitude.second,
			)
		}
	}

	private suspend fun loadCommittedCells(
		resolutionM: Int,
		fromMs: Long,
		toMs: Long,
		bounds: ObservedPresenceBounds,
	): List<PresenceCellAggregateRow> {
		if (toMs <= fromMs) return emptyList()
		val dao = database.presenceAnalysisDao()
		return bounds.longitudeWindowsE7().flatMap { longitude ->
			dao.getViewportCells(
				metricKind = PresenceCompactor.METRIC_KIND,
				modelKey = PresenceCompactor.MODEL_KEY,
				gridVersion = MetricAnalysisGrid.GRID_VERSION,
				resolutionM = resolutionM,
				fromMs = fromMs,
				toMs = toMs,
				southE7 = bounds.south.toE7(),
				northE7 = bounds.north.toE7(),
				westE7 = longitude.first,
				eastE7 = longitude.second,
			)
		}
	}

	private fun uncertaintyResolutionFloor(histogram: List<SupportBucketRow>, resolvedMs: Long): Int {
		if (resolvedMs <= 0L) return MetricAnalysisGrid.RESOLUTIONS_M.first()
		val targetMs = resolvedMs * MIN_REPRESENTED_FRACTION
		val byResolution = histogram.associate { it.resolutionM to it.supportMs }
		var cumulativeMs = 0L
		MetricAnalysisGrid.RESOLUTIONS_M.forEach { resolution ->
			cumulativeMs += byResolution[resolution] ?: 0L
			if (cumulativeMs >= targetMs) return resolution
		}
		return MetricAnalysisGrid.RESOLUTIONS_M.last()
	}

	private fun boundaryFragments(
		fromMs: Long,
		toMs: Long,
		fullFromMs: Long,
		fullToMs: Long,
	): List<TimeWindow> {
		val candidates = buildList {
			if (fromMs < fullFromMs) add(TimeWindow(fromMs, minOf(toMs, fullFromMs)))
			val tailStart = maxOf(fromMs, fullToMs)
			if (tailStart < toMs) add(TimeWindow(tailStart, toMs))
		}.filter { it.toMs > it.fromMs }.sortedBy { it.fromMs }
		if (candidates.size < 2) return candidates
		val merged = ArrayList<TimeWindow>(candidates.size)
		candidates.forEach { window ->
			val previous = merged.lastOrNull()
			if (previous != null && window.fromMs <= previous.toMs) {
				merged[merged.lastIndex] = previous.copy(toMs = maxOf(previous.toMs, window.toMs))
			} else {
				merged += window
			}
		}
		return merged
	}

	private fun normalizeResolution(requestedM: Int): Int =
		MetricAnalysisGrid.RESOLUTIONS_M.firstOrNull { it >= requestedM }
			?: MetricAnalysisGrid.RESOLUTIONS_M.last()

	private fun maxResolution(first: Int, second: Int): Int = maxOf(first, second)

	private fun nextCoarserResolution(resolutionM: Int): Int? {
		val index = MetricAnalysisGrid.RESOLUTIONS_M.indexOf(resolutionM)
		return MetricAnalysisGrid.RESOLUTIONS_M.getOrNull(index + 1)
	}

	private fun coverageState(
		materializationComplete: Boolean,
		totalMs: Long,
		unresolvedMs: Long,
		resolvedMs: Long,
		supportedMs: Long,
		omittedPresentationMs: Long,
	): ObservedPresenceCoverageState = when {
		!materializationComplete -> ObservedPresenceCoverageState.MATERIALIZING
		totalMs <= 0L -> ObservedPresenceCoverageState.EMPTY
		unresolvedMs <= 0L && supportedMs >= resolvedMs && omittedPresentationMs == 0L ->
			ObservedPresenceCoverageState.COMPLETE
		else -> ObservedPresenceCoverageState.PARTIAL
	}

	private fun emptySnapshot(resolutionM: Int) = ObservedPresenceSnapshot(
		cells = emptyList(),
		resolutionM = resolutionM,
		isCellBudgetTruncated = false,
		metricKind = PresenceCompactor.METRIC_KIND,
		modelKey = PresenceCompactor.MODEL_KEY,
		estimatorVersion = PresenceCompactor.ESTIMATOR_VERSION,
		calibrationVersion = PresenceCompactor.CALIBRATION_VERSION,
		gridVersion = MetricAnalysisGrid.GRID_VERSION,
		coverage = ObservedPresenceCoverage(
			state = ObservedPresenceCoverageState.EMPTY,
			trackedSeconds = 0.0,
			spatiallyObservedSeconds = 0.0,
			spatiallyInferredSeconds = 0.0,
			spatiallyUnresolvedSeconds = 0.0,
			supportedAtDisplayResolutionSeconds = 0.0,
			omittedPresentationSeconds = 0.0,
		),
	)

	private data class TimeWindow(val fromMs: Long, val toMs: Long)

	private data class ClippedPresence(val row: PresenceInterval, val millis: Long)

	private class MutableCellMass(
		val cellId: String,
		val resolutionM: Int,
		val minLatE7: Int,
		val minLonE7: Int,
		val maxLatE7: Int,
		val maxLonE7: Int,
		var expectedMs: Long = 0L,
		var observedMs: Long = 0L,
		var inferredMs: Long = 0L,
	) {
		constructor(cell: AnalysisCell) : this(
			cellId = cell.cellId,
			resolutionM = cell.resolutionM,
			minLatE7 = cell.minLatE7,
			minLonE7 = cell.minLonE7,
			maxLatE7 = cell.maxLatE7,
			maxLonE7 = cell.maxLonE7,
		)

		constructor(row: PresenceCellAggregateRow) : this(
			cellId = row.cellId,
			resolutionM = row.resolutionM,
			minLatE7 = row.minLatE7,
			minLonE7 = row.minLonE7,
			maxLatE7 = row.maxLatE7,
			maxLonE7 = row.maxLonE7,
		)

		fun add(row: PresenceCellAggregateRow) {
			expectedMs += row.expectedMs
			observedMs += row.observedMs
			inferredMs += row.inferredMs
		}

		fun add(other: MutableCellMass) {
			expectedMs += other.expectedMs
			observedMs += other.observedMs
			inferredMs += other.inferredMs
		}

		fun copyWithoutMass() = MutableCellMass(
			cellId,
			resolutionM,
			minLatE7,
			minLonE7,
			maxLatE7,
			maxLonE7,
		)

		fun toApi() = ObservedPresenceCell(
			cellId = cellId,
			resolutionM = resolutionM,
			north = maxLatE7 / 1e7,
			east = maxLonE7 / 1e7,
			south = minLatE7 / 1e7,
			west = minLonE7 / 1e7,
			expectedSeconds = expectedMs / MILLIS_PER_SECOND,
			observedSeconds = observedMs / MILLIS_PER_SECOND,
			inferredSeconds = inferredMs / MILLIS_PER_SECOND,
		)
	}

	private fun AnalysisCell.intersects(bounds: ObservedPresenceBounds): Boolean {
		if (maxLatE7 < bounds.south.toE7() || minLatE7 > bounds.north.toE7()) return false
		val westE7 = bounds.west.toE7()
		val eastE7 = bounds.east.toE7()
		return if (bounds.crossesAntimeridian) {
			maxLonE7 >= westE7 || minLonE7 <= eastE7
		} else {
			maxLonE7 >= westE7 && minLonE7 <= eastE7
		}
	}

	private fun ObservedPresenceBounds.longitudeWindowsE7(): List<Pair<Int, Int>> =
		if (crossesAntimeridian) {
			listOf(west.toE7() to MAX_LON_E7, MIN_LON_E7 to east.toE7())
		} else {
			listOf(west.toE7() to east.toE7())
		}

	private fun Double.toE7(): Int = (this * 1e7).roundToInt()

	private fun startOfUtcDay(timeMs: Long): Long = timeMs - (timeMs % UTC_DAY_MS)

	private fun ceilUtcDay(timeMs: Long): Long {
		val floor = startOfUtcDay(timeMs)
		return if (floor == timeMs) floor else floor + UTC_DAY_MS
	}

	private companion object {
		const val UTC_DAY_MS = 86_400_000L
		const val MILLIS_PER_SECOND = 1_000.0
		const val MAX_DIRECT_PARTITIONS = 62L
		const val MAX_PROGRESSIVE_PARTITIONS = 366
		const val MIN_REPRESENTED_FRACTION = 0.9
		const val MIN_LON_E7 = -1_800_000_000
		const val MAX_LON_E7 = 1_800_000_000
		const val STATE_OBSERVED = "OBSERVED"
		const val STATE_UNRESOLVED = "UNRESOLVED"

		val WORLD_BOUNDS = ObservedPresenceBounds(
			north = 90.0,
			east = 180.0,
			south = -90.0,
			west = -180.0,
		)
	}
}

internal data class PresenceFeatureBudget<T>(
	val included: List<T>,
	val omittedMs: Long,
	val truncated: Boolean,
)

/** Applies presentation limits without mutating or renormalizing canonical observed-presence mass. */
internal fun <T> applyPresenceFeatureBudget(
	ordered: List<T>,
	maxFeatures: Int,
	expectedMs: (T) -> Long,
): PresenceFeatureBudget<T> {
	require(maxFeatures > 0)
	val included = ordered.take(maxFeatures)
	val omittedMs = ordered.drop(maxFeatures).sumOf(expectedMs)
	return PresenceFeatureBudget(
		included = included,
		omittedMs = omittedMs,
		truncated = omittedMs > 0L,
	)
}
