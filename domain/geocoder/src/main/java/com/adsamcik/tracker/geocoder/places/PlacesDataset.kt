package com.adsamcik.tracker.geocoder.places

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.atan2
import kotlin.math.abs
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
 * Coordinates are stored as 1e-7 degree integers. Records remain grouped into a
 * fixed-size grid for compact generation, while nearest-place queries scan raw
 * coordinates directly to guarantee a globally correct result.
 */
class PlacesDataset(private val bytes: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val placeCount: Int

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
        val cellSizeE7 = buffer.getInt(12)
        val cellCount = buffer.getInt(16)
        require(placeCount >= 0 && cellSizeE7 > 0 && cellCount >= 0) { "places.geo corrupt header" }

        val placesOffsetLong = HEADER_SIZE.toLong() + cellCount.toLong() * CELL_ENTRY_SIZE
        val stringOffsetLong = placesOffsetLong + placeCount.toLong() * RECORD_SIZE
        require(stringOffsetLong <= bytes.size) { "places.geo truncated" }
        placesOffset = placesOffsetLong.toInt()
        stringOffset = stringOffsetLong.toInt()
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

    private fun normalizedNameAt(index: Int): String {
        val base = placesOffset + index * RECORD_SIZE
        val nameOff = buffer.getInt(base + 20)
        val nameLen = buffer.getShort(base + 24).toInt() and 0xFFFF
        return String(bytes, stringOffset + nameOff, nameLen, Charsets.UTF_8)
    }

    /**
     * Nearest place to ([latE7], [lonE7]), or `null` for an out-of-range latitude.
     * Longitude is wrapped into [-180, 180) before a deterministic full spherical scan.
     */
    fun nearest(latE7: Int, lonE7: Int): GeoPlace? {
        if (placeCount == 0) return null
        val query = normalizeQuery(latE7, lonE7) ?: return null
        val queryLat = query.latE7 / E7
        val queryLon = query.lonE7 / E7

        var bestIndex = -1
        var bestDistance = Double.POSITIVE_INFINITY
        var bestPopulation = Int.MIN_VALUE
        for (index in 0 until placeCount) {
            val base = placesOffset + index * RECORD_SIZE
            val candidateLat = buffer.getInt(base) / E7
            val candidateLon = buffer.getInt(base + 4) / E7
            val population = buffer.getInt(base + 8)
            val distance = haversineMeters(queryLat, queryLon, candidateLat, candidateLon)
            val tied = abs(distance - bestDistance) <= DISTANCE_TIE_EPSILON_METRES
            // Equal-distance places prefer larger population, then the lower asset record index.
            if (
                bestIndex < 0 ||
                distance < bestDistance - DISTANCE_TIE_EPSILON_METRES ||
                tied && (
                    population > bestPopulation ||
                        population == bestPopulation && index < bestIndex
                    )
            ) {
                bestIndex = index
                bestDistance = distance
                bestPopulation = population
            }
        }
        return if (bestIndex >= 0) placeAt(bestIndex) else null
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
        val near = if (nearLatE7 != null && nearLonE7 != null) {
            normalizeQuery(nearLatE7, nearLonE7) ?: return emptyList()
        } else {
            null
        }

        val matches = ArrayList<Scored>()
        for (i in 0 until placeCount) {
            val norm = normalizedNameAt(i)
            val matchScore = when {
                norm == needle -> 3
                norm.startsWith(needle) -> 2
                norm.contains(needle) -> 1
                else -> 0
            }
            if (matchScore == 0) continue
            val place = placeAt(i)
            val proximity = if (near != null) {
                -haversineMeters(
                    near.latE7 / E7,
                    near.lonE7 / E7,
                    place.latitude,
                    place.longitude,
                )
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

    private fun normalizeQuery(latE7: Int, lonE7: Int): QueryCoordinates? {
        if (latE7 !in MIN_LATITUDE_E7..MAX_LATITUDE_E7) return null
        val normalizedLon =
            Math.floorMod(lonE7.toLong() + HALF_LONGITUDE_RANGE_E7, LONGITUDE_RANGE_E7) -
                HALF_LONGITUDE_RANGE_E7
        return QueryCoordinates(latE7, normalizedLon.toInt())
    }

    private data class QueryCoordinates(val latE7: Int, val lonE7: Int)
    private class Scored(val place: GeoPlace, val matchScore: Int, val tieBreak: Double)

    companion object {
        private val MAGIC = "TGEO".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 2
        private const val HEADER_SIZE = 20
        private const val CELL_ENTRY_SIZE = 16
        private const val RECORD_SIZE = 28
        private const val MIN_LATITUDE_E7 = -900_000_000
        private const val MAX_LATITUDE_E7 = 900_000_000
        private const val HALF_LONGITUDE_RANGE_E7 = 1_800_000_000L
        private const val LONGITUDE_RANGE_E7 = 3_600_000_000L
        private const val E7 = 10_000_000.0
        private const val DISTANCE_TIE_EPSILON_METRES = 1e-6

        /** Great-circle distance in metres (exposed for callers/tests). */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p = Math.PI / 180.0
            val deltaLongitude = normalizeLongitudeDegrees(lon2 - lon1)
            val a = (
                sin((lat2 - lat1) * p / 2).let { it * it } +
                    cos(lat1 * p) * cos(lat2 * p) *
                    sin(deltaLongitude * p / 2).let { it * it }
                ).coerceIn(0.0, 1.0)
            return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1.0 - a))
        }

        private fun normalizeLongitudeDegrees(longitude: Double): Double =
            ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

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
