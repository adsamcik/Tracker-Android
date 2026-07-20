package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.GridTile
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceBounds
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceCoverage
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceRepository
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceRequest
import com.adsamcik.tracker.stats.api.repository.ObservedPresenceSnapshot
import kotlin.math.ln1p
import kotlin.math.roundToInt

/**
 * Uncertainty-aware observation-supported presence. V1 deliberately makes no stationary/dwell
 * claim; it renders accepted fixes' bounded temporal support and reports unsupported time.
 */
class ObservedPresenceLayer(
	private val repository: ObservedPresenceRepository,
) : BaseMapLayer<ObservedPresenceSnapshot, ObservedPresenceSnapshot>(), SupportsDateRange {

	override var dateRange: LongRange = 0L..Long.MAX_VALUE

	@Volatile
	var lastCoverage: ObservedPresenceCoverage? = null
		private set

	override suspend fun loadData(context: Context, bounds: Bounds?): ObservedPresenceSnapshot {
		val now = System.currentTimeMillis()
		val selected = dateRange
		val fromMs = (if (selected.isEmpty()) {
			(now - DEFAULT_RANGE_MS).coerceAtLeast(0L)
		} else {
			selected.first.coerceAtLeast(0L)
		}).coerceAtMost(Long.MAX_VALUE - 1L)
		val selectedLast = if (selected.isEmpty() || selected.last == Long.MAX_VALUE) now else selected.last
		val toMsExclusive = if (selectedLast == Long.MAX_VALUE) Long.MAX_VALUE else selectedLast + 1L
		val budgets = currentPerformanceBudgets()
		return repository.load(
			ObservedPresenceRequest(
				fromMs = fromMs,
				toMsExclusive = maxOf(fromMs + 1L, toMsExclusive),
				bounds = bounds?.let {
					ObservedPresenceBounds(
						north = it.north,
						east = it.east,
						south = it.south,
						west = it.west,
					)
				},
				preferredResolutionM = preferredResolutionForZoom(zoom),
				maxCells = (budgets.maxPoints / CELL_BUDGET_DIVISOR)
					.coerceIn(MIN_CELL_BUDGET, MAX_CELL_BUDGET),
			),
		)
	}

	override fun processData(
		input: ObservedPresenceSnapshot,
		budgets: PerformanceManager.PerformanceBudgets,
	): ObservedPresenceSnapshot {
		lastCoverage = input.coverage
		return input
	}

	override fun produceConfig(processed: ObservedPresenceSnapshot): MapLibreLayerConfig? {
		if (processed.cells.isEmpty()) return null
		val tiles = processed.cells.map { cell ->
			GridTile(
				west = cell.west,
				south = cell.south,
				east = cell.east,
				north = cell.north,
				weight = absolutePresenceWeight(cell.expectedSeconds),
				count = cell.expectedSeconds.coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt(),
			)
		}
		return MapLibreLayerConfig.Fill(
			geoJson = GeoJsonConverter.tilesToFeatureCollection(tiles),
			colorStops = HeatmapColorRamps.ObservedPresenceTime,
			opacity = PRESENCE_OPACITY,
			outlineColorArgb = PRESENCE_OUTLINE_COLOR,
			weightProperty = "weight",
		)
	}

	override fun onDisable() {
		lastCoverage = null
	}

	private fun absolutePresenceWeight(seconds: Double): Double =
		(ln1p(seconds.coerceAtLeast(0.0)) / ln1p(REFERENCE_PRESENCE_SECONDS)).coerceIn(0.0, 1.0)

	private fun preferredResolutionForZoom(zoom: Float): Int = when {
		zoom >= 16f -> 25
		zoom >= 15f -> 50
		zoom >= 14f -> 100
		zoom >= 13f -> 250
		zoom >= 12f -> 500
		else -> 1_000
	}

	private companion object {
		const val DEFAULT_RANGE_MS = 30L * 24L * 60L * 60L * 1_000L
		const val CELL_BUDGET_DIVISOR = 4
		const val MIN_CELL_BUDGET = 1_000
		const val MAX_CELL_BUDGET = 20_000
		const val REFERENCE_PRESENCE_SECONDS = 8.0 * 60.0 * 60.0
		const val PRESENCE_OPACITY = 0.74f
		const val PRESENCE_OUTLINE_COLOR = 0x332A1B5D
	}
}
