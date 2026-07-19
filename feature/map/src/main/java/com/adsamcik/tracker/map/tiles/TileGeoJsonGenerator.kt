package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.paddedBounds
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
        val buffered = requireNotNull(
            paddedBounds(
                tileBounds.north,
                tileBounds.east,
                tileBounds.south,
                tileBounds.west,
                paddingFraction = 0.1,
            ),
        )

        val tilePoints = points.filter {
            it.lat in buffered.south..buffered.north &&
                buffered.containsLongitude(it.lon)
        }

        return if (tilePoints.isEmpty()) {
            EMPTY_FEATURE_COLLECTION
        } else {
            GeoJsonConverter.pointsToFeatureCollection(tilePoints)
        }
    }

    /**
     * Generate a GeoJSON FeatureCollection with clipped LineString segments for a single tile.
     *
     * Extracts the segments of the polyline that intersect the tile bounds.
     * Lines that exit and re-enter the tile produce separate LineString features.
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

        val buffered = requireNotNull(
            paddedBounds(
                tileBounds.north,
                tileBounds.east,
                tileBounds.south,
                tileBounds.west,
                paddingFraction = 0.1,
            ),
        )

        val segments = clipLineToTileSegments(points, buffered)
        return if (segments.isEmpty()) {
            EMPTY_FEATURE_COLLECTION
        } else {
            GeoJsonConverter.segmentsToFeatureCollection(segments)
        }
    }

    /**
     * Clip a polyline to the given bounds, producing separate segments
     * for each contiguous run of points that intersects the bounds.
     * Each segment includes one-point overlap at entry/exit for line continuity.
     */
    internal fun clipLineToTileSegments(
        points: List<LatLngModel>,
        bounds: Bounds,
    ): List<List<LatLngModel>> {
        if (points.size < 2) return emptyList()

        val segments = mutableListOf<List<LatLngModel>>()
        var currentSegment = mutableListOf<LatLngModel>()
        var prevInside = false

        for (i in points.indices) {
            val p = points[i]
            val inside = p.lat in bounds.south..bounds.north &&
                bounds.containsLongitude(p.lng)

            if (inside) {
                // Entering the tile — include the outside predecessor for continuity
                if (!prevInside && i > 0) {
                    currentSegment.add(points[i - 1])
                }
                currentSegment.add(p)
            } else if (prevInside && currentSegment.isNotEmpty()) {
                // Exiting the tile — include exit point, then close the segment
                currentSegment.add(p)
                segments.add(currentSegment)
                currentSegment = mutableListOf()
            }
            prevInside = inside
        }

        // Flush any trailing in-bounds segment
        if (currentSegment.size >= 2) {
            segments.add(currentSegment)
        }

        return segments
    }

    /**
     * Legacy single-list clip. Delegates to [clipLineToTileSegments] and
     * returns the first segment (for backward compatibility).
     */
    internal fun clipLineToTile(
        points: List<LatLngModel>,
        bounds: Bounds,
    ): List<LatLngModel> {
        return clipLineToTileSegments(points, bounds).firstOrNull() ?: emptyList()
    }
}
