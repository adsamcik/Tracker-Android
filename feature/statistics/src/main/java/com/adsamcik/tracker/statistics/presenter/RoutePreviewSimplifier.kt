package com.adsamcik.tracker.statistics.presenter

import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Statistics-owned route compaction for the trip-detail preview.
 *
 * The map feature has richer rendering geometry, but statistics only needs
 * shape-aware Douglas-Peucker reduction with a hard point budget.
 */
internal object RoutePreviewSimplifier {
    fun simplify(
        points: List<RoutePoint>,
        toleranceMeters: Double,
        maxPoints: Int,
    ): List<RoutePoint> {
        if (points.size <= 2 || maxPoints < 2 || points.size <= maxPoints) {
            return points
        }

        val simplified = if (toleranceMeters > 0.0) {
            douglasPeucker(points, toleranceMeters)
        } else {
            points
        }
        return if (simplified.size > maxPoints) {
            simplifyToBudget(points, maxPoints, toleranceMeters)
        } else {
            simplified
        }
    }

    private fun simplifyToBudget(
        points: List<RoutePoint>,
        maxPoints: Int,
        startTolerance: Double,
    ): List<RoutePoint> {
        var tolerance = startTolerance.takeIf { it > 0.0 }
            ?: INITIAL_BUDGET_TOLERANCE_METERS
        var result = douglasPeucker(points, tolerance)
        var iterations = 0
        while (result.size > maxPoints && iterations < MAX_BUDGET_ITERATIONS) {
            tolerance *= BUDGET_TOLERANCE_GROWTH
            result = douglasPeucker(points, tolerance)
            iterations++
        }
        return if (result.size > maxPoints) {
            downSampleEvenly(result, maxPoints)
        } else {
            result
        }
    }

    private fun douglasPeucker(
        points: List<RoutePoint>,
        toleranceMeters: Double,
    ): List<RoutePoint> {
        if (points.size < 3) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = IntArray(points.size * 2)
        var stackSize = 0
        stack[stackSize++] = 0
        stack[stackSize++] = points.lastIndex

        while (stackSize > 0) {
            val end = stack[--stackSize]
            val start = stack[--stackSize]
            var maxDistance = 0.0
            var farthestIndex = -1
            val segment = ProjectedSegment(points[start], points[end])

            for (index in start + 1 until end) {
                val distance = segment.perpendicularDistanceMeters(points[index])
                if (distance > maxDistance) {
                    maxDistance = distance
                    farthestIndex = index
                }
            }

            if (maxDistance > toleranceMeters && farthestIndex != -1) {
                keep[farthestIndex] = true
                stack[stackSize++] = start
                stack[stackSize++] = farthestIndex
                stack[stackSize++] = farthestIndex
                stack[stackSize++] = end
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun downSampleEvenly(
        points: List<RoutePoint>,
        maxPoints: Int,
    ): List<RoutePoint> {
        if (points.size <= maxPoints) return points

        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val result = ArrayList<RoutePoint>(maxPoints)
        var cursor = 0.0
        while (result.size < maxPoints) {
            result += points[cursor.toInt()]
            cursor += step
        }
        result[result.lastIndex] = points.last()
        return result
    }

    private class ProjectedSegment(
        private val start: RoutePoint,
        end: RoutePoint,
    ) {
        private val latitudeRadians = Math.toRadians((start.lat + end.lat) / 2.0)
        private val longitudeScale = cos(latitudeRadians)
        private val endX = Math.toRadians(end.lng - start.lng) * longitudeScale
        private val endY = Math.toRadians(end.lat - start.lat)
        private val lengthSquared = endX * endX + endY * endY

        fun perpendicularDistanceMeters(point: RoutePoint): Double {
            if (lengthSquared == 0.0) {
                return distanceMeters(start, point)
            }

            val pointX = Math.toRadians(point.lng - start.lng) * longitudeScale
            val pointY = Math.toRadians(point.lat - start.lat)
            val projection = ((pointX * endX) + (pointY * endY)) / lengthSquared
            val clampedProjection = projection.coerceIn(0.0, 1.0)
            val deltaX = pointX - endX * clampedProjection
            val deltaY = pointY - endY * clampedProjection
            return sqrt(deltaX * deltaX + deltaY * deltaY) * EARTH_RADIUS_METERS
        }
    }

    private fun distanceMeters(
        first: RoutePoint,
        second: RoutePoint,
    ): Double {
        val latitudeDelta = Math.toRadians(second.lat - first.lat)
        val longitudeDelta = Math.toRadians(second.lng - first.lng)
        val firstLatitude = Math.toRadians(first.lat)
        val secondLatitude = Math.toRadians(second.lat)
        val haversine = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
            cos(firstLatitude) * cos(secondLatitude) *
            kotlin.math.sin(longitudeDelta / 2).let { it * it }
        val centralAngle = 2 * kotlin.math.atan2(sqrt(haversine), sqrt(1 - haversine))
        return EARTH_RADIUS_METERS * centralAngle
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val INITIAL_BUDGET_TOLERANCE_METERS = 1.0
    private const val BUDGET_TOLERANCE_GROWTH = 1.8
    private const val MAX_BUDGET_ITERATIONS = 40
}
