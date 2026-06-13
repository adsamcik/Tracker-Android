package com.adsamcik.tracker.geocoder.places

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A populated place from the bundled GeoNames-derived dataset. */
data class GeoPlace(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val name: String,
    val countryCode: String,
    val population: Int,
) {
    val latitude: Double get() = latitudeE7 / 1e7
    val longitude: Double get() = longitudeE7 / 1e7
}

/**
 * Pure (Android-free) reader and query engine for the `places.geo` binary asset.
 *
 * The format is documented in `tools/geocoder/build_places.py` and MUST stay in
 * sync with it. Decoupled from asset loading so it can be unit-tested with a
 * synthetic in-memory buffer.
 *
 * Coordinates are stored as 1e-7 degree integers; places are grouped into a
 * fixed-size lat/lon grid so a nearest-place query only scans a handful of cells.
 */
class PlacesDataset(private val bytes: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val placeCount: Int
    private val cellSizeE7: Int
    private val cellCount: Int

    /** Cell keys, ascending — enables binary search. Parallel to [cellStarts]/[cellCounts]. */
    private val cellKeys: LongArray
    private val cellStarts: IntArray
    private val cellCounts: IntArray

    private val placesOffset: Int
    private val stringOffset: Int

    init {
        require(bytes.size >= HEADER_SIZE) { "places.geo too small (${bytes.size} bytes)" }
        val magic = ByteArray(4)
        buffer.position(0)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) { "places.geo bad magic" }
        val version = buffer.get(4).toInt() and 0xFF
        require(version == VERSION) { "places.geo unsupported version $version" }

        placeCount = buffer.getInt(8)
        cellSizeE7 = buffer.getInt(12)
        cellCount = buffer.getInt(16)
        require(placeCount >= 0 && cellSizeE7 > 0 && cellCount >= 0) { "places.geo corrupt header" }

        cellKeys = LongArray(cellCount)
        cellStarts = IntArray(cellCount)
        cellCounts = IntArray(cellCount)
        var p = HEADER_SIZE
        for (i in 0 until cellCount) {
            cellKeys[i] = buffer.getLong(p)
            cellStarts[i] = buffer.getInt(p + 8)
            cellCounts[i] = buffer.getInt(p + 12)
            p += CELL_ENTRY_SIZE
        }
        placesOffset = HEADER_SIZE + cellCount * CELL_ENTRY_SIZE
        stringOffset = placesOffset + placeCount * RECORD_SIZE
    }

    /** Read the place record at [index] (0-based). */
    fun placeAt(index: Int): GeoPlace {
        val base = placesOffset + index * RECORD_SIZE
        val latE7 = buffer.getInt(base)
        val lonE7 = buffer.getInt(base + 4)
        val population = buffer.getInt(base + 8)
        val c0 = buffer.get(base + 12).toInt() and 0xFF
        val c1 = buffer.get(base + 13).toInt() and 0xFF
        val country = String(charArrayOf(c0.toChar(), c1.toChar())).trim()
        val nameOff = buffer.getInt(base + 14)
        val nameLen = buffer.getShort(base + 18).toInt() and 0xFFFF
        val name = String(bytes, stringOffset + nameOff, nameLen, Charsets.UTF_8)
        return GeoPlace(latE7, lonE7, name, country, population)
    }

    /**
     * Nearest place to ([latE7], [lonE7]). Searches the containing grid cell and
     * progressively wider rings (up to [MAX_RING]) until a candidate is found, then
     * returns the closest by distance.
     */
    fun nearest(latE7: Int, lonE7: Int): GeoPlace? {
        if (placeCount == 0) return null
        val latCell = Math.floorDiv(latE7, cellSizeE7)
        val lonCell = Math.floorDiv(lonE7, cellSizeE7)

        var best: GeoPlace? = null
        var bestDistSq = Double.MAX_VALUE
        var ring = 0
        while (ring <= MAX_RING) {
            forEachCellInRing(latCell, lonCell, ring) { ci ->
                val start = cellStarts[ci]
                val end = start + cellCounts[ci]
                for (idx in start until end) {
                    val place = placeAt(idx)
                    val d = approxDistSq(latE7, lonE7, place.latitudeE7, place.longitudeE7)
                    if (d < bestDistSq) {
                        bestDistSq = d
                        best = place
                    }
                }
            }
            // Once we have a candidate, scan one extra ring to catch a closer place
            // just across a cell boundary, then stop.
            if (best != null && ring >= 1) break
            ring++
        }
        return best
    }

    /**
     * Forward search: places whose (diacritic-insensitive) name matches [query].
     * Prefix matches rank above substring matches; ties break by proximity when
     * [nearLatE7]/[nearLonE7] are given, otherwise by population.
     */
    fun search(
        query: String,
        nearLatE7: Int?,
        nearLonE7: Int?,
        limit: Int,
    ): List<GeoPlace> {
        val needle = normalizeForSearch(query)
        if (needle.isEmpty() || limit <= 0) return emptyList()

        val matches = ArrayList<Scored>()
        for (i in 0 until placeCount) {
            val place = placeAt(i)
            val norm = normalizeForSearch(place.name)
            val matchScore = when {
                norm == needle -> 3
                norm.startsWith(needle) -> 2
                norm.contains(needle) -> 1
                else -> 0
            }
            if (matchScore == 0) continue
            val proximity = if (nearLatE7 != null && nearLonE7 != null) {
                -approxDistSq(nearLatE7, nearLonE7, place.latitudeE7, place.longitudeE7)
            } else {
                place.population.toDouble()
            }
            matches.add(Scored(place, matchScore, proximity))
        }
        matches.sortWith(
            compareByDescending<Scored> { it.matchScore }.thenByDescending { it.tieBreak },
        )
        return matches.take(limit).map { it.place }
    }

    private inline fun forEachCellInRing(
        latCell: Int,
        lonCell: Int,
        ring: Int,
        action: (cellIndex: Int) -> Unit,
    ) {
        if (ring == 0) {
            cellIndexOf(latCell, lonCell)?.let(action)
            return
        }
        var dLat = -ring
        while (dLat <= ring) {
            var dLon = -ring
            while (dLon <= ring) {
                // Only the outer border of the ring (interior was covered by smaller rings).
                if (kotlin.math.abs(dLat) == ring || kotlin.math.abs(dLon) == ring) {
                    cellIndexOf(latCell + dLat, lonCell + dLon)?.let(action)
                }
                dLon++
            }
            dLat++
        }
    }

    private fun cellIndexOf(latCell: Int, lonCell: Int): Int? {
        val key = (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)
        val i = java.util.Arrays.binarySearch(cellKeys, key)
        return if (i >= 0) i else null
    }

    private class Scored(val place: GeoPlace, val matchScore: Int, val tieBreak: Double)

    companion object {
        private val MAGIC = "TGEO".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val HEADER_SIZE = 20
        private const val CELL_ENTRY_SIZE = 16
        private const val RECORD_SIZE = 20
        private const val MAX_RING = 4
        private const val METRES_PER_E7_DEG = 0.01112

        /**
         * Squared planar distance in metres using an equirectangular approximation,
         * sufficient for ranking nearby candidates without full haversine cost.
         */
        private fun approxDistSq(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int): Double {
            val cosLat = cos(aLatE7 / 1e7 * Math.PI / 180.0)
            val dx = (bLonE7 - aLonE7).toDouble() * METRES_PER_E7_DEG * cosLat
            val dy = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
            return dx * dx + dy * dy
        }

        /** Great-circle distance in metres (exposed for callers/tests). */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p = Math.PI / 180.0
            val a = sin((lat2 - lat1) * p / 2).let { it * it } +
                cos(lat1 * p) * cos(lat2 * p) * sin((lon2 - lon1) * p / 2).let { it * it }
            return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Lowercase + strip diacritics for diacritic-insensitive matching. */
        fun normalizeForSearch(s: String): String {
            val decomposed = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
            val stripped = buildString(decomposed.length) {
                for (ch in decomposed) {
                    if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
                }
            }
            return stripped.lowercase()
        }
    }
}
