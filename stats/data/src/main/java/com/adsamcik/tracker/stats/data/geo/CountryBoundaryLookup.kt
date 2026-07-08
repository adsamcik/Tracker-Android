package com.adsamcik.tracker.stats.data.geo

import android.content.Context
import com.adsamcik.tracker.stats.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fully-offline reverse lookup from a WGS-84 coordinate to an ISO 3166-1 alpha-2
 * country code.
 *
 * Backed by a bundled, simplified Natural Earth (1:110m, public domain) country
 * boundary set in `res/raw/country_boundaries.json` — a compact custom format
 * (`[{iso, bb:[minLon,minLat,maxLon,maxLat], polys:[[[ [lon,lat], … ]]] }]`) with
 * coordinates rounded to ~11 m precision. No network is ever touched; the dataset
 * is parsed once, lazily, and cached for the process lifetime.
 *
 * Lookup is a per-country bounding-box reject followed by even-odd ray-casting
 * over each polygon's rings (so interior holes — e.g. Lesotho inside South
 * Africa — resolve correctly).
 */
@Singleton
class CountryBoundaryLookup @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	/** polygons is per-polygon → per-ring flat `[lon, lat, lon, lat, …]`. */
	private class Country(
		val iso: String,
		val minLon: Double,
		val minLat: Double,
		val maxLon: Double,
		val maxLat: Double,
		val polygons: Array<Array<DoubleArray>>,
	)

	private val countries: List<Country> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }

	/** Returns the ISO alpha-2 code containing the point, or null (e.g. open ocean). */
	fun countryOf(latDeg: Double, lonDeg: Double): String? {
		for (country in countries) {
			if (lonDeg < country.minLon || lonDeg > country.maxLon ||
				latDeg < country.minLat || latDeg > country.maxLat
			) {
				continue
			}
			for (polygon in country.polygons) {
				if (pointInPolygon(lonDeg, latDeg, polygon)) return country.iso
			}
		}
		return null
	}

	private fun pointInPolygon(x: Double, y: Double, rings: Array<DoubleArray>): Boolean {
		var inside = false
		for (ring in rings) {
			val vertices = ring.size / 2
			var j = vertices - 1
			for (i in 0 until vertices) {
				val xi = ring[i * 2]
				val yi = ring[i * 2 + 1]
				val xj = ring[j * 2]
				val yj = ring[j * 2 + 1]
				if (((yi > y) != (yj > y)) &&
					(x < (xj - xi) * (y - yi) / (yj - yi) + xi)
				) {
					inside = !inside
				}
				j = i
			}
		}
		return inside
	}

	private fun load(): List<Country> {
		val text = context.resources.openRawResource(R.raw.country_boundaries)
			.bufferedReader()
			.use { it.readText() }
		val array = JSONArray(text)
		val result = ArrayList<Country>(array.length())
		for (i in 0 until array.length()) {
			val obj = array.getJSONObject(i)
			val bb = obj.getJSONArray("bb")
			val polysJson = obj.getJSONArray("polys")
			val polygons = Array(polysJson.length()) { pi ->
				val ringsJson = polysJson.getJSONArray(pi)
				Array(ringsJson.length()) { ri ->
					val ringJson = ringsJson.getJSONArray(ri)
					DoubleArray(ringJson.length() * 2).also { flat ->
						for (ci in 0 until ringJson.length()) {
							val pt = ringJson.getJSONArray(ci)
							flat[ci * 2] = pt.getDouble(0)
							flat[ci * 2 + 1] = pt.getDouble(1)
						}
					}
				}
			}
			result.add(
				Country(
					iso = obj.getString("iso"),
					minLon = bb.getDouble(0),
					minLat = bb.getDouble(1),
					maxLon = bb.getDouble(2),
					maxLat = bb.getDouble(3),
					polygons = polygons,
				),
			)
		}
		return result
	}
}
