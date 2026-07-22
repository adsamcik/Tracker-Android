package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import com.adsamcik.tracker.shared.model.geo.CircularLongitude
import com.adsamcik.tracker.shared.model.geo.CircularLongitudeInterval
import com.adsamcik.tracker.shared.model.geo.ConservativeRadiusBounds
import com.adsamcik.tracker.shared.model.geo.GeoCoordinates

/** A bounded result for a grid-cell materialization request. */
sealed interface OsmCellCoverage {
	/** The requested coverage fit within the declared materialization cap. */
	class Available internal constructor(val cellKeys: LongArray) : OsmCellCoverage

	/** Materializing this many keys would exceed the grid's explicit cap. */
	data class TooLarge(
		val requestedCellCount: Long,
		val maximumCellCount: Int,
	) : OsmCellCoverage
}

/**
 * Coarse 0.01° grid used to index OSM ways for nearest-road lookup.
 *
 * Longitude cells cover exactly one canonical 36,000-cell cycle. New callers
 * receive an [OsmCellCoverage] so excessive coverage becomes a deliberate
 * abstention rather than an ambiguous empty array.
 */
object OsmGridIndex {

	const val CELL_DEGREES = 0.01
	const val CELL_E7 = 100_000

	/** Every SQL IN chunk stays under SQLite's conservative bind cap. */
	const val MAX_SQL_IN_BINDINGS = 900

	/**
	 * Bounds materialized grid keys, independent of caller input. A bigger
	 * request must use a range-oriented query or return an explicit abstention.
	 */
	const val MAX_CELLS_PER_COVERAGE = 20_000

	/** Compatibility name for callers that still use [cellKeysForBbox]. */
	@Deprecated("Use MAX_CELLS_PER_COVERAGE with typed coverage")
	const val MAX_CELLS_PER_BBOX = MAX_CELLS_PER_COVERAGE

	/** Returns the canonical grid key for a checked Earth coordinate. */
	fun cellKey(latE7: Int, lonE7: Int): Long {
		val latCell = latitudeCell(latE7)
		val lonCell = longitudeCell(lonE7)
		return pack(latCell, lonCell)
	}

	/**
	 * Covers a persisted bbox whose longitude endpoints are explicitly directed:
	 * `start -> end` travels eastward. This endpoint adapter can represent a
	 * [CircularLongitudeInterval.Point] or [CircularLongitudeInterval.Arc], but
	 * deliberately cannot infer [CircularLongitudeInterval.Full]. In particular,
	 * `-180°` and `+180°` are aliases of the same canonical endpoint, so that
	 * pair is a point rather than an implicit worldwide interval.
	 *
	 * Callers that already hold logical bounds must pass them to
	 * [cellCoverageForBounds] instead; its explicit [CircularLongitudeInterval.Full]
	 * state is the only way to request full-world longitude coverage. Current OSM
	 * production rejects Full ways before persistence, and legacy ordered extrema
	 * are producer-version-gated rather than reinterpreted here.
	 */
	fun cellCoverageForBbox(
		minLatE7: Int,
		maxLatE7: Int,
		startLonE7: Int,
		endLonE7: Int,
	): OsmCellCoverage {
		requireLatitudeRange(minLatE7, maxLatE7)
		requireLongitudeInput(startLonE7)
		requireLongitudeInput(endLonE7)
		return cellCoverageForBounds(
			minLatE7 = minLatE7,
			maxLatE7 = maxLatE7,
			longitude = CircularLongitudeInterval.fromDirectedEndpoints(
				startLonE7.toLong(),
				endLonE7.toLong(),
			),
		)
	}

	/** Covers a checked radius using the shared conservative WGS-84 angular bound. */
	fun cellCoverageForRadius(
		center: CheckedCoordinateE7,
		radiusMetres: Double,
	): OsmCellCoverage {
		val bounds = ConservativeRadiusBounds.around(center, radiusMetres)
		return cellCoverageForBounds(bounds.minLatitudeE7, bounds.maxLatitudeE7, bounds.longitude)
	}

	/** Covers already-checked latitude and circular-longitude bounds. */
	fun cellCoverageForBounds(
		minLatE7: Int,
		maxLatE7: Int,
		longitude: CircularLongitudeInterval,
	): OsmCellCoverage {
		requireLatitudeRange(minLatE7, maxLatE7)
		val minLatCell = latitudeCell(minLatE7)
		val maxLatCell = latitudeCell(maxLatE7)
		val rows = maxLatCell.toLong() - minLatCell.toLong() + 1L
		val longitudeRanges = longitude.toOrdinaryRangesE7().map { range ->
			longitudeCell(range.minE7) to longitudeCell(range.maxE7)
		}
		val columns = longitudeRanges.sumOf { (minCell, maxCell) ->
			maxCell.toLong() - minCell.toLong() + 1L
		}
		val cellCount = rows * columns
		if (cellCount > MAX_CELLS_PER_COVERAGE) {
			return OsmCellCoverage.TooLarge(cellCount, MAX_CELLS_PER_COVERAGE)
		}

		val keys = LongArray(cellCount.toInt())
		var index = 0
		for (latCell in minLatCell..maxLatCell) {
			for ((minLonCell, maxLonCell) in longitudeRanges) {
				for (lonCell in minLonCell..maxLonCell) {
					keys[index++] = pack(latCell, lonCell)
				}
			}
		}
		return OsmCellCoverage.Available(keys)
	}

	/**
	 * Legacy array API retained only while the old parser is separately gated.
	 * New lookup and reindex code must handle [OsmCellCoverage.TooLarge]
	 * explicitly rather than treating it as an empty coverage. Its historical
	 * `minLonE7`/`maxLonE7` parameter names now forward as directed
	 * `start`/`eastward-end` endpoints and therefore cannot represent Full.
	 */
	@Deprecated("Use cellCoverageForBbox and handle TooLarge explicitly")
	fun cellKeysForBbox(
		minLatE7: Int,
		maxLatE7: Int,
		minLonE7: Int,
		maxLonE7: Int,
	): LongArray = when (
		val coverage = cellCoverageForBbox(minLatE7, maxLatE7, minLonE7, maxLonE7)
	) {
		is OsmCellCoverage.Available -> coverage.cellKeys
		is OsmCellCoverage.TooLarge -> LongArray(0)
	}

	/**
	 * Fixed 3x3 compatibility neighborhood. It is kept for existing matcher
	 * behavior; metre-radius callers must use [cellCoverageForRadius].
	 */
	fun cellAnd8Neighbors(latE7: Int, lonE7: Int): LongArray = fixedNeighbors(latE7, lonE7)

	/** Alias retained for existing callers; no public arbitrary-radius API remains. */
	fun cellAndNeighbors(latE7: Int, lonE7: Int): LongArray = fixedNeighbors(latE7, lonE7)

	internal fun normalizeLongitudeE7(lonE7: Int): Int =
		CircularLongitude.normalizeE7(lonE7.toLong()).toInt()

	private fun fixedNeighbors(latE7: Int, lonE7: Int): LongArray {
		val centerLat = latitudeCell(latE7)
		val centerLon = longitudeCell(lonE7)
		val minLat = maxOf(MIN_LAT_CELL, centerLat - 1)
		val maxLat = minOf(MAX_LAT_CELL, centerLat + 1)
		val rows = maxLat - minLat + 1
		val keys = LongArray(rows * 3)
		var index = 0
		for (latCell in minLat..maxLat) {
			for (offset in -1..1) {
				keys[index++] = pack(latCell, wrapLongitudeCell(centerLon + offset))
			}
		}
		return keys
	}

	private fun requireLatitudeRange(minLatE7: Int, maxLatE7: Int) {
		require(minLatE7 <= maxLatE7) { "minLatE7 ($minLatE7) > maxLatE7 ($maxLatE7)" }
		require(minLatE7.toLong() in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7) {
			"minLatE7 ($minLatE7) outside [-90°, 90°]"
		}
		require(maxLatE7.toLong() in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7) {
			"maxLatE7 ($maxLatE7) outside [-90°, 90°]"
		}
	}

	private fun requireLongitudeInput(lonE7: Int) {
		require(lonE7.toLong() in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7) {
			"Longitude E7 ($lonE7) outside [-180°, 180°]"
		}
	}

	private fun latitudeCell(latE7: Int): Int {
		require(latE7.toLong() in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7) {
			"Latitude E7 ($latE7) outside [-90°, 90°]"
		}
		return Math.floorDiv(latE7, CELL_E7)
	}

	private fun longitudeCell(lonE7: Int): Int {
		requireLongitudeInput(lonE7)
		return Math.floorDiv(normalizeLongitudeE7(lonE7), CELL_E7)
	}

	private fun wrapLongitudeCell(cell: Int): Int =
		Math.floorMod(cell.toLong() - MIN_LON_CELL, LON_CELL_COUNT.toLong())
			.plus(MIN_LON_CELL)
			.toInt()

	private fun pack(latCell: Int, lonCell: Int): Long =
		(latCell.toLong() shl 24) or (lonCell.toLong() and 0xFFFFFFL)

	private const val MIN_LAT_CELL = -9_000
	private const val MAX_LAT_CELL = 9_000
	private const val MIN_LON_CELL = -18_000
	private const val LON_CELL_COUNT = 36_000
}
