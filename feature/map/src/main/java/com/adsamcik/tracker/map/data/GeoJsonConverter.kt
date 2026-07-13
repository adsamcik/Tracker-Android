package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import kotlin.math.round

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
     * Convert an ordered list of weighted geo features to a GeoJSON FeatureCollection containing a
     * single LineString (the vertices in path order). The per-vertex weights are carried separately
     * as pre-resolved gradient stops, so the geometry itself needs no properties.
     */
    fun weightedLineToFeatureCollection(path: List<WeightedGeoFeature>): String {
        val sb = StringBuilder(path.size * 40 + 200)
        sb.append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
        path.forEachIndexed { index, point ->
            if (index > 0) sb.append(',')
            sb.append('[')
            sb.append(point.lon)
            sb.append(',')
            sb.append(point.lat)
            sb.append(']')
        }
        sb.append("""]},"properties":{}}]}""")
        return sb.toString()
    }

    /**
     * Convert weighted paths into one two-vertex LineString feature per edge. The edge receives the
     * mean heat of its endpoints in [weightProperty], allowing a normal data-driven LineLayer to
     * colour many discontinuous paths without the global line-progress limitations of line-gradient.
     * A positive [mergeToleranceDegrees] emits spatially equivalent undirected edges only once, so
     * repeated visits cannot increase opacity after the heat calculation has suppressed them.
     */
    fun weightedSegmentsToFeatureCollection(
        paths: List<List<WeightedGeoFeature>>,
        weightProperty: String = "weight",
        mergeToleranceDegrees: Double = 0.0,
    ): String {
        val edges = ArrayList<WeightedEdge>(paths.sumOf { (it.size - 1).coerceAtLeast(0) })
        paths.forEach { path ->
            for (index in 1 until path.size) {
                val from = path[index - 1]
                val to = path[index]
                edges += WeightedEdge(
                    from = from,
                    to = to,
                    weight = ((from.weight + to.weight) / 2.0).coerceIn(0.0, 1.0),
                )
            }
        }
        if (edges.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""

        val renderEdges = if (mergeToleranceDegrees > 0.0) {
            val unique = LinkedHashMap<SegmentKey, WeightedEdge>()
            edges.forEach { edge ->
                val key = edge.segmentKey(mergeToleranceDegrees)
                val previous = unique[key]
                if (previous == null || edge.weight > previous.weight ||
                    (edge.weight == previous.weight && edge.newestTime > previous.newestTime)
                ) {
                    unique[key] = edge
                }
            }
            unique.values
        } else {
            edges
        }

        val sb = StringBuilder(renderEdges.size * 180 + 100)
        sb.append("""{"type":"FeatureCollection","features":[""")
        renderEdges.forEachIndexed { index, edge ->
            if (index > 0) sb.append(',')
            sb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            appendLonLat(sb, edge.from.lon, edge.from.lat)
            sb.append(',')
            appendLonLat(sb, edge.to.lon, edge.to.lat)
            sb.append("]},\"properties\":{\"")
            sb.append(weightProperty)
            sb.append("\":")
            sb.append(edge.weight)
            sb.append("}}")
        }
        sb.append("]}")
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
     * Convert multiple line segments to a GeoJSON FeatureCollection with
     * one LineString Feature per segment. Used by tile clipping where a
     * polyline may cross a tile boundary multiple times.
     */
    fun segmentsToFeatureCollection(segments: List<List<LatLngModel>>): String {
        val validSegments = segments.filter { it.size >= 2 }
        if (validSegments.isEmpty()) {
            return """{"type":"FeatureCollection","features":[]}"""
        }
        if (validSegments.size == 1) {
            return lineToFeatureCollection(validSegments.first())
        }
        val sb = StringBuilder(validSegments.sumOf { it.size } * 40 + 200)
        sb.append("""{"type":"FeatureCollection","features":[""")
        validSegments.forEachIndexed { segIndex, segment ->
            if (segIndex > 0) sb.append(',')
            sb.append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
            segment.forEachIndexed { ptIndex, point ->
                if (ptIndex > 0) sb.append(',')
                sb.append('[')
                sb.append(point.lng)
                sb.append(',')
                sb.append(point.lat)
                sb.append(']')
            }
            sb.append("""]},"properties":{}}""")
        }
        sb.append("""]}""")
        return sb.toString()
    }

    /**
     * Convert a single lat/lng to a GeoJSON Point Feature.
     */
    fun pointToFeature(lat: Double, lng: Double): String =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{}}"""

    /**
     * Convert grid tiles to a GeoJSON FeatureCollection of rectangular [Polygon]s. Each feature
     * carries a normalized "weight" property in [0, 1] for data-driven fill colouring. Used by the
     * legacy grid-tile heatmap.
     */
    fun tilesToFeatureCollection(tiles: List<GridTile>): String {
        if (tiles.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
        val sb = StringBuilder(tiles.size * 170 + 100)
        sb.append("""{"type":"FeatureCollection","features":[""")
        tiles.forEachIndexed { index, tile ->
            if (index > 0) sb.append(',')
            sb.append("""{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
            // Closed ring: SW -> SE -> NE -> NW -> SW
            appendLonLat(sb, tile.west, tile.south); sb.append(',')
            appendLonLat(sb, tile.east, tile.south); sb.append(',')
            appendLonLat(sb, tile.east, tile.north); sb.append(',')
            appendLonLat(sb, tile.west, tile.north); sb.append(',')
            appendLonLat(sb, tile.west, tile.south)
            sb.append("""]]},"properties":{"weight":""")
            sb.append(tile.weight)
            sb.append("""}}""")
        }
        sb.append("""]}""")
        return sb.toString()
    }

    private data class WeightedEdge(
        val from: WeightedGeoFeature,
        val to: WeightedGeoFeature,
        val weight: Double,
    ) {
        val newestTime: Long = maxOf(from.time, to.time)

        fun segmentKey(tolerance: Double): SegmentKey {
            val fromNode = SegmentNode(
                latBucket = round(from.lat / tolerance).toLong(),
                lonBucket = round(from.lon / tolerance).toLong(),
            )
            val toNode = SegmentNode(
                latBucket = round(to.lat / tolerance).toLong(),
                lonBucket = round(to.lon / tolerance).toLong(),
            )
            return if (fromNode.isBefore(toNode)) {
                SegmentKey(fromNode, toNode)
            } else {
                SegmentKey(toNode, fromNode)
            }
        }
    }

    private data class SegmentNode(val latBucket: Long, val lonBucket: Long) {
        fun isBefore(other: SegmentNode): Boolean =
            latBucket < other.latBucket ||
                (latBucket == other.latBucket && lonBucket <= other.lonBucket)
    }

    private data class SegmentKey(
        val first: SegmentNode,
        val second: SegmentNode,
    )

    private fun appendLonLat(sb: StringBuilder, lon: Double, lat: Double) {
        sb.append('[')
        sb.append(lon)
        sb.append(',')
        sb.append(lat)
        sb.append(']')
    }
}
