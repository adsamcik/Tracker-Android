package com.adsamcik.tracker.map.graphics

import com.google.android.gms.maps.model.LatLng
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
     * Simplify a polyline with Douglas-Peucker then enforce [maxPoints] by down sampling and optional even spacing.
     * Primary entry used internally; prefer the simpler overload unless custom spacing control required.
     */
    fun optimize(
        points: List<LatLng>,
        toleranceMeters: Double,
        maxPoints: Int,
        evenSpacing: Boolean = true,
    ): List<LatLng> {
        if (points.size <= 2 || (points.size <= maxPoints && toleranceMeters <= 0.0)) return points
        val simplified = if (toleranceMeters > 0) douglasPeucker(points, toleranceMeters) else points
        val capped = if (simplified.size > maxPoints) downSampleEvenly(simplified, maxPoints) else simplified
        if (!evenSpacing) return capped
        return if (capped.size <= 2) capped else resampleEvenDistance(capped, min(maxPoints, capped.size))
    }

    /**
     * Spec-conforming overload (points, tolerance, maxPoints) -> List<LatLng> with even spacing enabled.
     */
    fun optimize(points: List<LatLng>, toleranceMeters: Double, maxPoints: Int): List<LatLng> =
        optimize(points, toleranceMeters, maxPoints, evenSpacing = true)

    private fun douglasPeucker(points: List<LatLng>, tolerance: Double): List<LatLng> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            val a = points[start]
            val b = points[end]
            for (i in start + 1 until end) {
                val d = perpendicularDistanceMeters(points[i], a, b)
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > tolerance && index != -1) {
                keep[index] = true
                stack.addLast(start to index)
                stack.addLast(index to end)
            }
        }
        val out = ArrayList<LatLng>(keep.count { it })
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    private fun downSampleEvenly(points: List<LatLng>, maxPoints: Int): List<LatLng> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val out = ArrayList<LatLng>(maxPoints)
        var acc = 0.0
        while (out.size < maxPoints) {
            val idx = acc.toInt()
            out.add(points[idx])
            acc += step
        }
        if (out.last() != points.last()) out[out.lastIndex] = points.last()
        return out
    }

    private fun resampleEvenDistance(points: List<LatLng>, desired: Int): List<LatLng> {
        if (desired <= 2 || points.size <= 2) return points
        val distances = DoubleArray(points.size)
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceMeters(points[i - 1], points[i])
            distances[i] = total
        }
        if (total == 0.0) return points
        val step = total / (desired - 1)
        val out = ArrayList<LatLng>(desired)
        out.add(points.first())
        var target = step
        var j = 1
        while (out.size < desired - 1 && j < points.size) {
            val prev = points[j - 1]
            val next = points[j]
            val prevDist = distances[j - 1]
            val nextDist = distances[j]
            while (target <= nextDist && out.size < desired - 1) {
                val ratio = (target - prevDist) / (nextDist - prevDist).coerceAtLeast(1e-9)
                out.add(interpolate(prev, next, ratio))
                target += step
            }
            j++
        }
        out.add(points.last())
        return out
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
        return R * c
    }

    private fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng = LatLng(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )

    private fun perpendicularDistanceMeters(c: LatLng, a: LatLng, b: LatLng): Double {
        val distAB = distanceMeters(a, b)
        if (distAB == 0.0) return distanceMeters(a, c)
        val latRad = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val xA = 0.0
        val yA = 0.0
        val xB = Math.toRadians(b.longitude - a.longitude) * cos(latRad)
        val yB = Math.toRadians(b.latitude - a.latitude)
        val xC = Math.toRadians(c.longitude - a.longitude) * cos(latRad)
        val yC = Math.toRadians(c.latitude - a.latitude)
        val proj = ((xC * xB) + (yC * yB)) / (xB * xB + yB * yB)
        val clamped = proj.coerceIn(0.0, 1.0)
        val xP = xA + xB * clamped
        val yP = yA + yB * clamped
        val dx = xC - xP
        val dy = yC - yP
        val earthR = 6371000.0
        return kotlin.math.sqrt(dx * dx + dy * dy) * earthR
    }
}
