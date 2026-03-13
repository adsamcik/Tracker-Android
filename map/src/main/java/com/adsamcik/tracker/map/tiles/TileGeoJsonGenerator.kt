package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.udf.LatLngModel

/**
 * Generates GeoJSON for a single tile's worth of data.
 * Instead of building a giant GeoJSON string for the entire dataset,
 * this produces small per-tile payloads that are typically 10-100x smaller.
 */
object TileGeoJsonGenerator {

    private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    /**
     * Generate a GeoJSON FeatureCollection of weighted points for a single tile.
     *
     * Points are filtered to [tileBounds] with a small buffer (10% of tile height)
     * to ensure smooth rendering at tile edges.
     *
     * @param points All candidate points (pre-filtered by DB bounds query or full dataset).
     * @param tileBounds The geographic bounds of the tile.
     * @return GeoJSON string containing only the points within the buffered tile bounds.
     */
    fun generatePointTile(
        points: List<WeightedGeoFeature>,
        tileBounds: Bounds,
    ): String {
        val buffer = (tileBounds.north - tileBounds.south) * 0.1
        val buffered = Bounds(
            north = (tileBounds.north + buffer).coerceAtMost(90.0),
            south = (tileBounds.south - buffer).coerceAtLeast(-90.0),
            east = (tileBounds.east + buffer).coerceAtMost(180.0),
            west = (tileBounds.west - buffer).coerceAtLeast(-180.0),
        )

        val tilePoints = points.filter {
            it.lat in buffered.south..buffered.north &&
                it.lon in buffered.west..buffered.east
        }

        return if (tilePoints.isEmpty()) {
            EMPTY_FEATURE_COLLECTION
        } else {
            GeoJsonConverter.pointsToFeatureCollection(tilePoints)
        }
    }

    /**
     * Generate a GeoJSON FeatureCollection with a clipped LineString for a single tile.
     *
     * Extracts the segments of the polyline that intersect the tile bounds.
     *
     * @param points Ordered polyline coordinates.
     * @param tileBounds The geographic bounds of the tile.
     * @return GeoJSON string containing line segments within the tile.
     */
    fun generateLineTile(
        points: List<LatLngModel>,
        tileBounds: Bounds,
    ): String {
        if (points.size < 2) return EMPTY_FEATURE_COLLECTION

        val buffer = (tileBounds.north - tileBounds.south) * 0.1
        val buffered = Bounds(
            north = (tileBounds.north + buffer).coerceAtMost(90.0),
            south = (tileBounds.south - buffer).coerceAtLeast(-90.0),
            east = (tileBounds.east + buffer).coerceAtMost(180.0),
            west = (tileBounds.west - buffer).coerceAtLeast(-180.0),
        )

        val clipped = clipLineToTile(points, buffered)
        return if (clipped.isEmpty()) {
            EMPTY_FEATURE_COLLECTION
        } else {
            GeoJsonConverter.lineToFeatureCollection(clipped)
        }
    }

    /**
     * Clip a polyline to the given bounds, keeping segments where at least
     * one endpoint is inside the bounds.
     */
    internal fun clipLineToTile(
        points: List<LatLngModel>,
        bounds: Bounds,
    ): List<LatLngModel> {
        if (points.size < 2) return emptyList()

        val result = mutableListOf<LatLngModel>()
        var prevInside = false

        for (i in points.indices) {
            val p = points[i]
            val inside = p.lat in bounds.south..bounds.north &&
                p.lng in bounds.west..bounds.east

            if (inside) {
                // If previous point was outside but this one is inside, include both
                // for line continuity
                if (!prevInside && i > 0 && result.isEmpty()) {
                    result.add(points[i - 1])
                }
                result.add(p)
            } else if (prevInside && result.isNotEmpty()) {
                // Exiting the tile — include this point for continuity, then stop segment
                result.add(p)
            }
            prevInside = inside
        }

        return result
    }
}
