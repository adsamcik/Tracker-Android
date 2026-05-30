package com.adsamcik.tracker.osm.io

/**
 * Coarse square-degree grid used to index OSM ways for nearest-road lookup.
 *
 * The cell size is `0.08°` (E7: 800_000) — roughly 9 km at the equator and
 * still meaningful at sub-arctic latitudes. The key packs the 16-bit latitude
 * cell index into the high half of a Long and the 24-bit longitude cell index
 * into the low half (signed division is intentional so latitudes/longitudes
 * either side of 0 don't collide).
 *
 * The same encoding is used by the SQL-backed `osm_way_cell` table, so any
 * change here MUST also be reflected in the migration.
 */
object OsmGridIndex {

	const val CELL_DEGREES = 0.08
	const val CELL_E7 = 800_000

	/** Returns the cell key for a single point in E7 coordinates. */
	fun cellKey(latE7: Int, lonE7: Int): Long {
		val latCell = floorDiv(latE7, CELL_E7).toLong()
		val lonCell = floorDiv(lonE7, CELL_E7).toLong()
		return (latCell shl 24) or (lonCell and 0xFFFFFFL)
	}

	/**
	 * Returns the set of cell keys that cover the given E7 bounding box.
	 * Always contains at least one cell (the cell containing the min corner).
	 */
	fun cellKeysForBbox(
		minLatE7: Int,
		maxLatE7: Int,
		minLonE7: Int,
		maxLonE7: Int,
	): LongArray {
		require(minLatE7 <= maxLatE7) { "minLatE7 ($minLatE7) > maxLatE7 ($maxLatE7)" }
		require(minLonE7 <= maxLonE7) { "minLonE7 ($minLonE7) > maxLonE7 ($maxLonE7)" }
		val latMinCell = floorDiv(minLatE7, CELL_E7)
		val latMaxCell = floorDiv(maxLatE7, CELL_E7)
		val lonMinCell = floorDiv(minLonE7, CELL_E7)
		val lonMaxCell = floorDiv(maxLonE7, CELL_E7)
		val rows = latMaxCell - latMinCell + 1
		val cols = lonMaxCell - lonMinCell + 1
		val out = LongArray(rows * cols)
		var idx = 0
		for (lat in latMinCell..latMaxCell) {
			for (lon in lonMinCell..lonMaxCell) {
				out[idx++] = (lat.toLong() shl 24) or (lon.toLong() and 0xFFFFFFL)
			}
		}
		return out
	}

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

	private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
}
