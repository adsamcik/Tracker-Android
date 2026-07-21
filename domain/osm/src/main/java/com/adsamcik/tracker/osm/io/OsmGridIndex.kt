package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.logging.api.ReporterFacade

/**
 * Coarse square-degree grid used to index OSM ways for nearest-road lookup.
 *
 * The cell size is `0.01°` (E7: 100_000) — roughly 1.1 km at the equator and
 * ~0.7 km at lat 50°. This sits one order of magnitude tighter than typical
 * road-segment lengths and matches the spatial scale of OSM tile resolution
 * around z14, giving the speed-limit lookup a much smaller candidate set per
 * query than the original 0.08° (~9 km) grid.
 *
 * The key packs the latitude cell index into the high half of a Long
 * (`shl 24`) and the longitude cell index into the low 24 bits with
 * `and 0xFFFFFF`. Signed floor division is intentional so latitudes /
 * longitudes either side of 0 don't collide. With a 0.01° cell:
 *
 *  - latitude cell range is roughly ±9_000 (well within 16 bits)
 *  - longitude cell range is roughly ±18_000 (well within the 24-bit signed
 *    slot ±8_388_607)
 *
 * The same encoding is used by the SQL-backed `osm_way_cell` table.
 * Because the key space is purely a function of the constants above,
 * **any change to [CELL_E7] invalidates every existing `osm_way_cell` row**
 * — `MIGRATION_31_32` drops the table and a background reindexer rebuilds it
 * from the preserved `osm_way` bboxes on first launch after upgrade.
 */
object OsmGridIndex {

	const val CELL_DEGREES = 0.01
	const val CELL_E7 = 100_000

	/**
	 * A single OSM way covering 20,000 0.01° cells is already implausibly large
	 * (for example, a 100 x 200-cell rectangle spans roughly 111 x 222 km at
	 * the equator). The cap keeps each returned array below 160 KB and prevents
	 * malformed or unexpectedly broad bboxes from turning into multi-GB arrays.
	 */
	internal const val MAX_CELLS_PER_BBOX = 20_000

	/** Returns the cell key for a single point in E7 coordinates. */
	fun cellKey(latE7: Int, lonE7: Int): Long {
		val latCell = floorDiv(latE7, CELL_E7).toLong()
		val lonCell = floorDiv(lonE7, CELL_E7).toLong()
		return (latCell shl 24) or (lonCell and 0xFFFFFFL)
	}

	/**
	 * Returns the set of cell keys that cover the given E7 bounding box.
	 *
	 * A longitude range with `minLonE7 > maxLonE7` represents an antimeridian
	 * crossing. For backwards compatibility with persisted bboxes produced by
	 * older imports, an ordered range wider than 180° is interpreted as the
	 * narrow complementary antimeridian range. Crossing ranges are split into
	 * two ordinary intervals. Returns an empty array when coverage would exceed
	 * [MAX_CELLS_PER_BBOX].
	 */
	fun cellKeysForBbox(
		minLatE7: Int,
		maxLatE7: Int,
		minLonE7: Int,
		maxLonE7: Int,
	): LongArray {
		require(minLatE7 <= maxLatE7) { "minLatE7 ($minLatE7) > maxLatE7 ($maxLatE7)" }
		require(minLonE7.toLong() in -HALF_WORLD_E7..HALF_WORLD_E7) {
			"minLonE7 ($minLonE7) outside [-180°, 180°]"
		}
		require(maxLonE7.toLong() in -HALF_WORLD_E7..HALF_WORLD_E7) {
			"maxLonE7 ($maxLonE7) outside [-180°, 180°]"
		}

		val latMinCell = floorDiv(minLatE7, CELL_E7)
		val latMaxCell = floorDiv(maxLatE7, CELL_E7)
		val rows = latMaxCell.toLong() - latMinCell.toLong() + 1L
		val rawLonSpan = maxLonE7.toLong() - minLonE7.toLong()
		if (minLonE7 <= maxLonE7 && rawLonSpan >= WORLD_E7) {
			return rejectExcessiveCoverage(rows * LON_CELL_COUNT)
		}

		val canonicalMinLon = normalizeLongitudeE7(minLonE7)
		val canonicalMaxLon = normalizeLongitudeE7(maxLonE7)
		val lonRanges = when {
			minLonE7 == maxLonE7 -> arrayOf(
				floorDiv(canonicalMinLon, CELL_E7) to floorDiv(canonicalMaxLon, CELL_E7),
			)
			minLonE7 > maxLonE7 -> crossingLongitudeRanges(minLonE7, maxLonE7)
			rawLonSpan > HALF_WORLD_E7 -> crossingLongitudeRanges(maxLonE7, minLonE7)
			maxLonE7.toLong() == HALF_WORLD_E7 -> arrayOf(
				floorDiv(canonicalMinLon, CELL_E7) to MAX_LON_CELL,
				MIN_LON_CELL to MIN_LON_CELL,
			)
			else -> arrayOf(
				floorDiv(canonicalMinLon, CELL_E7) to floorDiv(canonicalMaxLon, CELL_E7),
			)
		}
		val cols = lonRanges.sumOf { (minCell, maxCell) ->
			maxCell.toLong() - minCell.toLong() + 1L
		}
		val cellCount = rows * cols
		if (cellCount > MAX_CELLS_PER_BBOX) {
			return rejectExcessiveCoverage(cellCount)
		}

		val out = LongArray(cellCount.toInt())
		var idx = 0
		for (lat in latMinCell..latMaxCell) {
			for ((lonMinCell, lonMaxCell) in lonRanges) {
				for (lon in lonMinCell..lonMaxCell) {
					out[idx++] = (lat.toLong() shl 24) or (lon.toLong() and 0xFFFFFFL)
				}
			}
		}
		return out
	}

	private fun crossingLongitudeRanges(startLonE7: Int, endLonE7: Int): Array<Pair<Int, Int>> =
		arrayOf(
			floorDiv(startLonE7, CELL_E7) to MAX_LON_CELL,
			MIN_LON_CELL to floorDiv(endLonE7, CELL_E7),
		)

	/**
	 * Returns the cell key and its 8 immediate neighbours (3x3 block centred
	 * on the given point). Used by the speed-limit source to widen the search
	 * radius when the point sits near a cell boundary.
	 */
	fun cellAnd8Neighbors(latE7: Int, lonE7: Int): LongArray {
		val latCell = floorDiv(latE7, CELL_E7)
		val lonCell = floorDiv(lonE7, CELL_E7)
		val out = LongArray(9)
		var idx = 0
		for (dLat in -1..1) {
			for (dLon in -1..1) {
				val lat = (latCell + dLat).toLong()
				val lon = (lonCell + dLon).toLong()
				out[idx++] = (lat shl 24) or (lon and 0xFFFFFFL)
			}
		}
		return out
	}

	/**
	 * Returns cells around a point, wrapping longitude neighbours across the
	 * antimeridian. Unlike [cellAnd8Neighbors], this method supports a wider
	 * longitude radius for lookups at high latitudes, where a 0.01° cell is
	 * physically narrower.
	 */
	fun cellAndNeighbors(
		latE7: Int,
		lonE7: Int,
		latRadius: Int = 1,
		lonRadius: Int = 1,
	): LongArray {
		require(latRadius >= 0) { "latRadius must be non-negative" }
		require(lonRadius >= 0) { "lonRadius must be non-negative" }

		val latCell = floorDiv(latE7, CELL_E7)
		val lonCell = floorDiv(normalizeLongitudeE7(lonE7), CELL_E7)
		val minLatCell = maxOf(MIN_LAT_CELL, latCell - latRadius)
		val maxLatCell = minOf(MAX_LAT_CELL, latCell + latRadius)
		val effectiveLonRadius = minOf(lonRadius, HALF_LON_CELL_COUNT)
		val lonOffsets = if (effectiveLonRadius == HALF_LON_CELL_COUNT) {
			-HALF_LON_CELL_COUNT until HALF_LON_CELL_COUNT
		} else {
			-effectiveLonRadius..effectiveLonRadius
		}
		val rows = maxLatCell - minLatCell + 1
		val out = LongArray(rows * lonOffsets.count())
		var idx = 0
		for (lat in minLatCell..maxLatCell) {
			for (offset in lonOffsets) {
				val lon = wrapLongitudeCell(lonCell + offset).toLong()
				out[idx++] = (lat.toLong() shl 24) or (lon and 0xFFFFFFL)
			}
		}
		return out
	}

	private fun rejectExcessiveCoverage(cellCount: Long): LongArray {
		ReporterFacade.log(
			"OsmGridIndex: rejected bbox coverage of $cellCount cells " +
				"(limit=$MAX_CELLS_PER_BBOX)",
		)
		return LongArray(0)
	}

	internal fun normalizeLongitudeE7(lonE7: Int): Int =
		Math.floorMod(lonE7.toLong() + HALF_WORLD_E7, WORLD_E7).minus(HALF_WORLD_E7).toInt()

	private fun wrapLongitudeCell(cell: Int): Int =
		Math.floorMod(
			cell.toLong() - MIN_LON_CELL,
			LON_CELL_COUNT.toLong(),
		).plus(MIN_LON_CELL).toInt()

	private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)

	private const val WORLD_E7 = 3_600_000_000L
	private const val HALF_WORLD_E7 = WORLD_E7 / 2
	private const val MIN_LAT_CELL = -9_000
	private const val MAX_LAT_CELL = 9_000
	private const val MIN_LON_CELL = -18_000
	private const val MAX_LON_CELL = 17_999
	private const val LON_CELL_COUNT = 36_000
	private const val HALF_LON_CELL_COUNT = LON_CELL_COUNT / 2
}
