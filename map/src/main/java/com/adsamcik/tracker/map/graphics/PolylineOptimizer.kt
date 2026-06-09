package com.adsamcik.tracker.map.graphics

import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/**
 * Geometry utilities for reducing and regularizing polylines.
 * Implements Douglas-Peucker decimation followed by optional even spacing
 * + maxPoints enforcement.
 */
object PolylineOptimizer {

    /**
     * Simplify a polyline with Douglas-Peucker, then, if it is still over
     * [maxPoints], keep reducing it **shape-awarely** (by raising the
     * Douglas-Peucker tolerance) until it fits the budget.
     *
     * [evenSpacing] re-distributes the result into equal-distance samples. This
     * discards the shape-defining vertices Douglas-Peucker keeps (corners get
     * rounded off), so it defaults to `false` and should only be enabled when a
     * caller specifically wants uniform spacing (e.g. animation) rather than a
     * faithful route shape.
     */
    fun optimize(
        points: List<LatLngModel>,
        toleranceMeters: Double,
        maxPoints: Int,
        evenSpacing: Boolean = false,
    ): List<LatLngModel> {
        if (points.size <= 2 || maxPoints < 2) return points
        if (points.size <= maxPoints && toleranceMeters <= 0.0) return points

        var simplified = if (toleranceMeters > 0.0) douglasPeucker(points, toleranceMeters) else points
        if (simplified.size > maxPoints) {
            // Enforce the point budget by raising the tolerance until the shape
            // fits, instead of dropping points at fixed index intervals.
            simplified = simplifyToBudget(points, maxPoints, toleranceMeters)
        }

        if (!evenSpacing) return simplified
        return if (simplified.size <= 2) simplified else resampleEvenDistance(simplified, min(maxPoints, simplified.size))
    }

    /**
     * Reduce [points] to at most [maxPoints] using Douglas-Peucker, growing the
     * tolerance geometrically until the budget is met. This preserves the most
     * significant vertices (corners, turns) rather than blindly decimating.
     *
     * Falls back to even index down-sampling only if a very large tolerance still
     * cannot hit the budget (degenerate input such as a dense GPS cluster); even
     * then it keeps real recorded vertices rather than interpolating new ones.
     */
    private fun simplifyToBudget(
        points: List<LatLngModel>,
        maxPoints: Int,
        startTolerance: Double,
    ): List<LatLngModel> {
        var tolerance = if (startTolerance > 0.0) startTolerance else INITIAL_BUDGET_TOLERANCE_METERS
        var result = douglasPeucker(points, tolerance)
        var iterations = 0
        while (result.size > maxPoints && iterations < MAX_BUDGET_ITERATIONS) {
            tolerance *= BUDGET_TOLERANCE_GROWTH
            result = douglasPeucker(points, tolerance)
            iterations++
        }
        return if (result.size > maxPoints) downSampleEvenly(result, maxPoints) else result
    }

    private fun douglasPeucker(points: List<LatLngModel>, tolerance: Double): List<LatLngModel> {
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
            var maxDist = 0.0
            var index = -1
            val a = points[start]
            val b = points[end]
            val segment = ProjectedSegment(a, b)
            for (i in start + 1 until end) {
                val d = segment.perpendicularDistanceMeters(points[i])
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > tolerance && index != -1) {
                keep[index] = true
                stack[stackSize++] = start
                stack[stackSize++] = index
                stack[stackSize++] = index
                stack[stackSize++] = end
            }
        }
        var keepCount = 0
        for (shouldKeep in keep) {
            if (shouldKeep) keepCount++
        }
        val out = ArrayList<LatLngModel>(keepCount)
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    private fun downSampleEvenly(points: List<LatLngModel>, maxPoints: Int): List<LatLngModel> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val out = ArrayList<LatLngModel>(maxPoints)
        var acc = 0.0
        while (out.size < maxPoints) {
            val idx = acc.toInt()
            out.add(points[idx])
            acc += step
        }
        if (out.last() != points.last()) out[out.lastIndex] = points.last()
        return out
    }

    private fun resampleEvenDistance(points: List<LatLngModel>, desired: Int): List<LatLngModel> {
        if (desired <= 2 || points.size <= 2) return points
        val distances = DoubleArray(points.size)
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceMeters(points[i - 1], points[i])
            distances[i] = total
        }
        if (total == 0.0) return points
        val step = total / (desired - 1)
        val out = ArrayList<LatLngModel>(desired)
        out.add(points.first())
        var target = step
        var j = 1
        while (out.size < desired - 1 && j < points.size) {
            val prevDist = distances[j - 1]
            val nextDist = distances[j]
            while (target <= nextDist && out.size < desired - 1) {
                val ratio = (target - prevDist) / (nextDist - prevDist).coerceAtLeast(1e-9)
                out.add(interpolate(points[j - 1], points[j], ratio))
                target += step
            }
            j++
        }
        out.add(points.last())
        return out
    }

    private fun distanceMeters(a: LatLngModel, b: LatLngModel): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
        return R * c
    }

    private fun interpolate(a: LatLngModel, b: LatLngModel, t: Double): LatLngModel = LatLngModel(
        lat = a.lat + (b.lat - a.lat) * t,
        lng = a.lng + (b.lng - a.lng) * t,
    )

    private class ProjectedSegment(
        private val a: LatLngModel,
        b: LatLngModel,
    ) {
        private val latRad = Math.toRadians((a.lat + b.lat) / 2.0)
        private val cosLat = cos(latRad)
        private val xB = Math.toRadians(b.lng - a.lng) * cosLat
        private val yB = Math.toRadians(b.lat - a.lat)
        private val lengthSquared = xB * xB + yB * yB

        fun perpendicularDistanceMeters(c: LatLngModel): Double {
            if (lengthSquared == 0.0) return distanceMeters(a, c)
            val xC = Math.toRadians(c.lng - a.lng) * cosLat
            val yC = Math.toRadians(c.lat - a.lat)
            val proj = ((xC * xB) + (yC * yB)) / lengthSquared
            val clamped = proj.coerceIn(0.0, 1.0)
            val xP = xB * clamped
            val yP = yB * clamped
            val dx = xC - xP
            val dy = yC - yP
            val earthR = 6371000.0
            return kotlin.math.sqrt(dx * dx + dy * dy) * earthR
        }
    }

    /** Starting tolerance for adaptive budget simplification when none is supplied. */
    private const val INITIAL_BUDGET_TOLERANCE_METERS = 1.0

    /** Geometric growth factor applied to the tolerance each budget iteration. */
    private const val BUDGET_TOLERANCE_GROWTH = 1.8

    /** Safety cap on adaptive iterations; growth makes this converge well before the limit. */
    private const val MAX_BUDGET_ITERATIONS = 40
}
