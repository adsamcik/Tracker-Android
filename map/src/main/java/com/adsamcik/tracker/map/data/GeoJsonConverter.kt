package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.map.presentation.udf.LatLngModel

/**
 * Converts domain entities to GeoJSON strings for MapLibre consumption.
 */
object GeoJsonConverter {

    /**
     * Convert weighted geo features to a GeoJSON FeatureCollection of Points.
     * Each feature has a "weight" property for heatmap rendering.
     */
    fun pointsToFeatureCollection(points: List<WeightedGeoFeature>): String {
        val sb = StringBuilder(points.size * 80 + 100)
        sb.append("""{"type":"FeatureCollection","features":[""")
        points.forEachIndexed { index, point ->
            if (index > 0) sb.append(',')
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
            sb.append(point.lon)
            sb.append(',')
            sb.append(point.lat)
            sb.append("""]},"properties":{"weight":""")
            sb.append(point.weight)
            sb.append("""}}""")
        }
        sb.append("""]}""")
        return sb.toString()
    }

    /**
     * Convert a list of coordinate pairs to a GeoJSON FeatureCollection with a single LineString.
     */
    fun lineToFeatureCollection(points: List<LatLngModel>): String {
        val sb = StringBuilder(points.size * 40 + 200)
        sb.append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
        points.forEachIndexed { index, point ->
            if (index > 0) sb.append(',')
            sb.append('[')
            sb.append(point.lng)
            sb.append(',')
            sb.append(point.lat)
            sb.append(']')
        }
        sb.append("""]},"properties":{}}]}""")
        return sb.toString()
    }

    /**
     * Convert a single lat/lng to a GeoJSON Point Feature.
     */
    fun pointToFeature(lat: Double, lng: Double): String =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{}}"""
}
