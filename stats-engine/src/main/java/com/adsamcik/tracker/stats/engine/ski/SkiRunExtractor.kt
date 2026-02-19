package com.adsamcik.tracker.stats.engine.ski

/**
 * Represents an individual ski run with computed metrics.
 * This is the in-memory representation; SkiRunSegment is the Room entity.
 */
data class SkiRun(
    val runIndex: Int,
    val segmentType: SkiState,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val verticalM: Float,
    val distanceM: Float,
    val maxSpeedMps: Float,
    val avgSpeedMps: Float
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
}

/**
 * Summary statistics for a skiing session.
 */
data class SkiSessionSummary(
    val totalRuns: Int,
    val totalVerticalM: Float,
    val totalDistanceM: Float,
    val totalLiftTimeMs: Long,
    val totalRunTimeMs: Long,
    val runs: List<SkiRun>
)

/**
 * Location point for computing per-segment metrics.
 */
data class SkiLocationPoint(
    val timeMs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Float?,
    val speedMps: Float?
)

/**
 * Extracts individual ski runs from state machine output and computes per-segment metrics.
 * Pure function, no Android dependencies.
 */
object SkiRunExtractor {

    /**
     * Extract ski runs with metrics from state segments and location data.
     *
     * @param segments state machine output (ordered by time)
     * @param locations GPS location points (ordered by time)
     * @return session summary with individual runs
     */
    fun extract(
        segments: List<SkiStateSegment>,
        locations: List<SkiLocationPoint>
    ): SkiSessionSummary {
        val runs = segments.mapIndexed { index, segment ->
            val segmentLocations = locations.filter {
                it.timeMs in segment.startMs..segment.endMs
            }
            computeRunMetrics(index, segment, segmentLocations)
        }

        val downhillRuns = runs.filter { it.segmentType == SkiState.DOWNHILL_RUN }
        val liftRuns = runs.filter { it.segmentType == SkiState.LIFT_UP }

        return SkiSessionSummary(
            totalRuns = downhillRuns.size,
            totalVerticalM = downhillRuns.sumOf { it.verticalM.toDouble() }.toFloat(),
            totalDistanceM = downhillRuns.sumOf { it.distanceM.toDouble() }.toFloat(),
            totalLiftTimeMs = liftRuns.sumOf { it.durationMs },
            totalRunTimeMs = downhillRuns.sumOf { it.durationMs },
            runs = runs
        )
    }

    internal fun computeRunMetrics(
        index: Int,
        segment: SkiStateSegment,
        locations: List<SkiLocationPoint>
    ): SkiRun {
        val altitudes = locations.mapNotNull { it.altitudeM }
        val speeds = locations.mapNotNull { it.speedMps }

        val verticalDrop = if (altitudes.size >= 2) {
            altitudes.last() - altitudes.first()
        } else {
            0f
        }

        val totalDistance = computeTotalDistance(locations)
        val maxSpeed = speeds.maxOrNull() ?: 0f
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average().toFloat() else 0f

        return SkiRun(
            runIndex = index,
            segmentType = segment.state,
            startTimeMs = segment.startMs,
            endTimeMs = segment.endMs,
            verticalM = verticalDrop,
            distanceM = totalDistance,
            maxSpeedMps = maxSpeed,
            avgSpeedMps = avgSpeed
        )
    }

    private fun computeTotalDistance(locations: List<SkiLocationPoint>): Float {
        if (locations.size < 2) return 0f
        var total = 0.0
        for (i in 1 until locations.size) {
            total += haversineDistance(
                locations[i - 1].latitudeDeg, locations[i - 1].longitudeDeg,
                locations[i].latitudeDeg, locations[i].longitudeDeg
            )
        }
        return total.toFloat()
    }

    /**
     * Haversine distance in meters between two lat/lon points.
     */
    internal fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6_371_000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }
}
