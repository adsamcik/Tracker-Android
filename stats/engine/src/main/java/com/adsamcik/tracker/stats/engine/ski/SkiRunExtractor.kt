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
     * @param config detection config for coalescing thresholds
     * @return session summary with individual runs
     */
    fun extract(
        segments: List<SkiStateSegment>,
        locations: List<SkiLocationPoint>,
        config: SkiDetectionConfig = SkiDetectionConfig()
    ): SkiSessionSummary {
        val coalescedGroups = coalesceRuns(segments, config)

        val runs = ArrayList<SkiRun>(coalescedGroups.size)
        var locationSearchStart = 0
        for (index in coalescedGroups.indices) {
            val group = coalescedGroups[index]
            val startMs = group.first().startMs
            val endMs = group.last().endMs
            while (locationSearchStart < locations.size && locations[locationSearchStart].timeMs < startMs) {
                locationSearchStart++
            }
            var locationEnd = locationSearchStart
            while (locationEnd < locations.size && locations[locationEnd].timeMs <= endMs) {
                locationEnd++
            }
            runs.add(computeRunMetrics(index, group, locations, locationSearchStart, locationEnd))
            locationSearchStart = locationEnd
        }

        var totalRuns = 0
        var totalVerticalM = 0f
        var totalDistanceM = 0f
        var totalLiftTimeMs = 0L
        var totalRunTimeMs = 0L
        for (run in runs) {
            when (run.segmentType) {
                SkiState.DOWNHILL_RUN -> {
                    totalRuns++
                    totalVerticalM += run.verticalM
                    totalDistanceM += run.distanceM
                    totalRunTimeMs += run.durationMs
                }
                SkiState.LIFT_UP -> totalLiftTimeMs += run.durationMs
                SkiState.IDLE -> Unit
                SkiState.WALK -> Unit
            }
        }

        return SkiSessionSummary(
            totalRuns = totalRuns,
            totalVerticalM = totalVerticalM,
            totalDistanceM = totalDistanceM,
            totalLiftTimeMs = totalLiftTimeMs,
            totalRunTimeMs = totalRunTimeMs,
            runs = runs
        )
    }

    /**
     * Group consecutive DOWNHILL segments separated only by non-LIFT gaps
     * into single logical run groups. You can't start a new downhill run
     * without going up first — only a LIFT between them splits runs.
     */
    internal fun coalesceRuns(
        segments: List<SkiStateSegment>,
        config: SkiDetectionConfig
    ): List<List<SkiStateSegment>> {
        if (segments.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<SkiStateSegment>>()
        var lastDownhillGroupIdx = -1
        var hasLiftSinceLastDownhill = false

        for (segment in segments) {
            if (segment.state == SkiState.LIFT_UP) {
                hasLiftSinceLastDownhill = true
            }

            val shouldCoalesce = segment.state == SkiState.DOWNHILL_RUN &&
                    lastDownhillGroupIdx >= 0 &&
                    !hasLiftSinceLastDownhill

            if (shouldCoalesce) {
                // Absorb all intermediate groups (IDLE gaps) into the last DOWNHILL group
                val targetGroup = groups[lastDownhillGroupIdx]
                while (groups.size > lastDownhillGroupIdx + 1) {
                    targetGroup.addAll(groups.removeAt(lastDownhillGroupIdx + 1))
                }
                targetGroup.add(segment)
                hasLiftSinceLastDownhill = false
            } else {
                groups.add(mutableListOf(segment))
                if (segment.state == SkiState.DOWNHILL_RUN) {
                    lastDownhillGroupIdx = groups.lastIndex
                    hasLiftSinceLastDownhill = false
                }
            }
        }
        return groups
    }

    internal fun computeRunMetrics(
        index: Int,
        group: List<SkiStateSegment>,
        locations: List<SkiLocationPoint>
    ): SkiRun = computeRunMetrics(index, group, locations, 0, locations.size)

    private fun computeRunMetrics(
        index: Int,
        group: List<SkiStateSegment>,
        locations: List<SkiLocationPoint>,
        startIndex: Int,
        endIndex: Int
    ): SkiRun {
        // For coalesced groups (DOWNHILL+IDLE+DOWNHILL), pick state from the actual runs
        var primaryDownhill: SkiStateSegment? = null
        var longestSegment = group.first()
        for (segment in group) {
            if (segment.durationMs > longestSegment.durationMs) {
                longestSegment = segment
            }
            if (segment.state == SkiState.DOWNHILL_RUN &&
                (primaryDownhill == null || segment.durationMs > primaryDownhill.durationMs)
            ) {
                primaryDownhill = segment
            }
        }
        val primarySegment = primaryDownhill ?: longestSegment
        val startMs = group.first().startMs
        val endMs = group.last().endMs
        var firstAltitude: Float? = null
        var lastAltitude: Float? = null
        var maxSpeed = 0f
        var speedSum = 0.0
        var speedCount = 0
        for (i in startIndex until endIndex) {
            val location = locations[i]
            val altitude = location.altitudeM
            if (altitude != null) {
                if (firstAltitude == null) {
                    firstAltitude = altitude
                }
                lastAltitude = altitude
            }
            val speed = location.speedMps
            if (speed != null) {
                if (speed > maxSpeed) {
                    maxSpeed = speed
                }
                speedSum += speed
                speedCount++
            }
        }

        val verticalDrop = if (firstAltitude != null && lastAltitude != null) {
            lastAltitude - firstAltitude
        } else {
            0f
        }

        val totalDistance = computeTotalDistance(locations, startIndex, endIndex)
        val avgSpeed = if (speedCount > 0) (speedSum / speedCount).toFloat() else 0f

        return SkiRun(
            runIndex = index,
            segmentType = primarySegment.state,
            startTimeMs = startMs,
            endTimeMs = endMs,
            verticalM = verticalDrop,
            distanceM = totalDistance,
            maxSpeedMps = maxSpeed,
            avgSpeedMps = avgSpeed
        )
    }

    private fun computeTotalDistance(
        locations: List<SkiLocationPoint>,
        startIndex: Int,
        endIndex: Int
    ): Float {
        if (endIndex - startIndex < 2) return 0f
        var total = 0.0
        for (i in startIndex + 1 until endIndex) {
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
