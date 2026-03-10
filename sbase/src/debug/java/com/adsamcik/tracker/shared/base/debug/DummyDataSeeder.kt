package com.adsamcik.tracker.shared.base.debug

import android.content.Context
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import com.adsamcik.tracker.shared.base.database.data.MotionState
import com.adsamcik.tracker.shared.base.database.data.SampleQuality
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import kotlin.random.Random

/**
 * Seeds dummy tracking data for debugging purposes.
 * Creates a realistic GPS trail through NYC with interpolated points and activity detection.
 */
object DummyDataSeeder {

    /**
     * Result of dummy data seeding operation
     */
    data class SeedResult(
        val inserted: Boolean,
        val reason: String = ""
    )

    /**
     * Represents a major checkpoint in the route
     */
    private data class Checkpoint(
        val lat: Double,
        val lon: Double,
        val activity: Int,
        val description: String
    )

    /**
     * Seeds the database with dummy data if it's empty
     */
    suspend fun seedIfEmpty(context: Context): SeedResult = withContext(Dispatchers.IO) {
        val database = AppDatabase.database(context)
        
        // Check if database already has data
        val existingTripsCount = database.tripDao().countAllTrips()
        if (existingTripsCount > 0) {
            return@withContext SeedResult(false, "not-empty")
        }

        try {
            database.runInTransaction {
                insertDummyData(database)
            }
            SeedResult(true)
        } catch (e: Exception) {
            SeedResult(false, "error: ${e.message}")
        }
    }

    /**
     * Seeds the database with dummy data regardless of current contents.
     * Useful for development flows that want to append synthetic sessions.
     */
    suspend fun seed(context: Context): SeedResult = withContext(Dispatchers.IO) {
        val database = AppDatabase.database(context)
        try {
            database.runInTransaction {
                insertDummyData(database)
            }
            SeedResult(true)
        } catch (e: Exception) {
            SeedResult(false, "error: ${e.message}")
        }
    }

    /**
     * Inserts dummy tracking data into the database
     */
    private fun insertDummyData(database: AppDatabase) {
        val locationSampleDao = database.locationSampleDao()
        val sessionSegmentDao = database.sessionSegmentDao()

        // Create NYC trail checkpoints (Central Park to Brooklyn Bridge)
        val checkpoints = listOf(
            Checkpoint(40.7741, -73.9714, DetectedActivity.WALKING, "Central Park - Sheep Meadow"),
            Checkpoint(40.7731, -73.9687, DetectedActivity.WALKING, "Central Park - The Mall"),
            Checkpoint(40.7648, -73.9731, DetectedActivity.WALKING, "Columbus Circle"),
            Checkpoint(40.7589, -73.9851, DetectedActivity.WALKING, "Lincoln Center"),
            Checkpoint(40.7505, -73.9934, DetectedActivity.WALKING, "Hell's Kitchen"),
            Checkpoint(40.7505, -73.9934, DetectedActivity.IN_VEHICLE, "Subway - Times Square"),
            Checkpoint(40.7282, -73.9942, DetectedActivity.IN_VEHICLE, "Subway - Union Square"),
            Checkpoint(40.7178, -73.9951, DetectedActivity.WALKING, "Greenwich Village"),
            Checkpoint(40.7074, -73.9977, DetectedActivity.WALKING, "SoHo"),
            Checkpoint(40.6892, -73.9925, DetectedActivity.WALKING, "Brooklyn Bridge approach"),
            Checkpoint(40.6959, -73.9969, DetectedActivity.WALKING, "Brooklyn Bridge walkway"),
            Checkpoint(40.6962, -73.9968, DetectedActivity.WALKING, "Brooklyn Bridge - Manhattan view"),
            Checkpoint(40.6959, -73.9969, DetectedActivity.WALKING, "Brooklyn Bridge center"),
            Checkpoint(40.6962, -73.9968, DetectedActivity.WALKING, "Brooklyn Heights Promenade")
        )

        // Plan 3 sessions reasonably spaced within the last 24 hours
        val now = System.currentTimeMillis()
        val hour = 60L * 60L * 1000L
        val sessionsPlan = listOf(
            // Morning-ish
            Pair(now - 20L * hour, 45L * 60L * 1000L),
            // Midday-ish
            Pair(now - 12L * hour, 60L * 60L * 1000L),
            // Evening-ish
            Pair(now - 4L * hour, 35L * 60L * 1000L)
        )

        sessionsPlan.forEach { (startTime, duration) ->
            // Generate interpolated points with realistic GPS behavior and evenly distribute over duration
            val locations = interpolatePoints(checkpoints, startTime, duration)

            val endTime = if (locations.isNotEmpty()) locations.last().time else startTime + duration
            val totalDistance = calculateTotalDistance(locations)
            val steps = Random.nextInt(4000, 12000)

            // Insert location samples
            locations.forEach { locationData ->
                val sample = LocationSample(
                    timeMs = locationData.time,
                    elapsedRealtimeNanos = 0L,
                    latE7 = (locationData.lat * 1e7).toInt(),
                    lonE7 = (locationData.lon * 1e7).toInt(),
                    altitudeM = locationData.altitude.toFloat(),
                    rawGpsAltitudeM = locationData.altitude.toFloat(),
                    hAccM = locationData.accuracy,
                    vAccM = null,
                    speedMps = locationData.speed,
                    speedAccuracyMps = null,
                    provider = "fused",
                    quality = SampleQuality.HIGH,
                    motionState = if (locationData.activity == DetectedActivity.WALKING) MotionState.MOVING else MotionState.MOVING,
                    policy = null,
                    bucketId = null,
                    createdAt = System.currentTimeMillis()
                )
                locationSampleDao.insert(sample)
            }

            // Insert matching session segment for trip DAO queries
            val segment = SessionSegment(
                startTimeMs = startTime,
                endTimeMs = endTime,
                distanceM = totalDistance,
                steps = steps,
                primaryActivity = DetectedActivity.WALKING,
                activityConfidence = Random.nextInt(75, 100),
                sampleCount = locations.size,
                source = SegmentSource.USER_CREATED,
                inferenceVersion = null,
                createdAt = System.currentTimeMillis()
            )
            sessionSegmentDao.insert(segment)
        }
    }

    /**
     * Data class for location with additional metadata
     */
    private data class LocationData(
        val time: Long,
        val lat: Double,
        val lon: Double,
        val altitude: Double,
        val accuracy: Float,
        val speed: Float,
        val activity: Int
    )

    /**
     * Interpolates points between checkpoints with realistic GPS behavior
     */
    private fun interpolatePoints(
        checkpoints: List<Checkpoint>,
        startTime: Long,
        durationMillis: Long
    ): List<LocationData> {
        val coords = mutableListOf<Triple<Double, Double, Int>>()

        for (i in 0 until checkpoints.size - 1) {
            val start = checkpoints[i]
            val end = checkpoints[i + 1]

            coords.add(Triple(start.lat, start.lon, start.activity))

            // Calculate distance and determine number of interpolation points
            val distance = haversineDistance(start.lat, start.lon, end.lat, end.lon)
            val stepSize = Random.nextDouble(5.0, 18.0) // 5-18 meters per step
            val numSteps = (distance / stepSize).toInt().coerceAtLeast(1)

            // Interpolate points between checkpoints
            for (step in 1 until numSteps) {
                val progress = step.toDouble() / numSteps
                val bearing = calculateBearing(start.lat, start.lon, end.lat, end.lon)
                val stepDistance = distance * progress

                val interpolatedPoint = movePoint(start.lat, start.lon, bearing, stepDistance)

                // Add some GPS drift
                val driftAngle = Random.nextDouble(0.0, 360.0)
                val driftDistance = Random.nextDouble(0.0, 15.0) // Up to 15m drift
                val driftedPoint = movePoint(interpolatedPoint.first, interpolatedPoint.second, driftAngle, driftDistance)

                coords.add(Triple(driftedPoint.first, driftedPoint.second, start.activity))
            }
        }

        // Add final checkpoint
        val lastCheckpoint = checkpoints.last()
        coords.add(Triple(lastCheckpoint.lat, lastCheckpoint.lon, lastCheckpoint.activity))

        val count = coords.size
        if (count == 0) return emptyList()
        val denom = (count - 1).coerceAtLeast(1)

        // Evenly distribute timestamps across the desired duration
        return coords.mapIndexed { index, (lat, lon, activity) ->
            val t = startTime + (durationMillis * index / denom)
            LocationData(
                time = t,
                lat = lat,
                lon = lon,
                altitude = generateAltitude(lat, lon),
                accuracy = generateAccuracy(activity),
                speed = generateSpeed(activity),
                activity = activity
            )
        }
    }

    /**
     * Calculate total distance for the session
     */
    private fun calculateTotalDistance(locations: List<LocationData>): Float {
        var totalDistance = 0.0
        for (i in 1 until locations.size) {
            val prev = locations[i - 1]
            val curr = locations[i]
            totalDistance += haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
        }
        return totalDistance.toFloat()
    }

    /**
     * Calculate walking distance
     */
    private fun calculateWalkingDistance(locations: List<LocationData>): Float {
        var walkingDistance = 0.0
        for (i in 1 until locations.size) {
            val prev = locations[i - 1]
            val curr = locations[i]
            if (curr.activity == DetectedActivity.WALKING) {
                walkingDistance += haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
            }
        }
        return walkingDistance.toFloat()
    }

    /**
     * Calculate vehicle distance
     */
    private fun calculateVehicleDistance(locations: List<LocationData>): Float {
        var vehicleDistance = 0.0
        for (i in 1 until locations.size) {
            val prev = locations[i - 1]
            val curr = locations[i]
            if (curr.activity == DetectedActivity.IN_VEHICLE) {
                vehicleDistance += haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
            }
        }
        return vehicleDistance.toFloat()
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * Calculate bearing between two points
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        return Math.toDegrees(atan2(y, x))
    }

    /**
     * Move a point by distance and bearing
     */
    private fun movePoint(lat: Double, lon: Double, bearing: Double, distance: Double): Pair<Double, Double> {
        val R = 6371000.0 // Earth radius in meters
        val bearingRad = Math.toRadians(bearing)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        
        val newLatRad = asin(sin(latRad) * cos(distance / R) + cos(latRad) * sin(distance / R) * cos(bearingRad))
        val newLonRad = lonRad + atan2(sin(bearingRad) * sin(distance / R) * cos(latRad), cos(distance / R) - sin(latRad) * sin(newLatRad))
        
        return Pair(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad))
    }

    /**
     * Generate realistic altitude based on NYC terrain
     */
    private fun generateAltitude(lat: Double, lon: Double): Double {
        // Brooklyn Bridge has elevation around 40m, Manhattan average ~10m
        val baseAltitude = if (lat < 40.7050) 40.0 else 10.0 // Rough Brooklyn Bridge area
        return baseAltitude + Random.nextDouble(-5.0, 15.0)
    }

    /**
     * Generate GPS accuracy based on activity type
     */
    private fun generateAccuracy(activity: Int): Float {
        return when (activity) {
            DetectedActivity.WALKING -> Random.nextFloat() * 8f + 3f // 3-11m
            DetectedActivity.IN_VEHICLE -> Random.nextFloat() * 15f + 8f // 8-23m
            else -> Random.nextFloat() * 10f + 5f // 5-15m
        }
    }

    /**
     * Generate speed based on activity type
     */
    private fun generateSpeed(activity: Int): Float {
        return when (activity) {
            DetectedActivity.WALKING -> Random.nextFloat() * 0.8f + 1.0f // 1.0-1.8 m/s
            DetectedActivity.IN_VEHICLE -> Random.nextFloat() * 10f + 3f // 3-13 m/s
            else -> Random.nextFloat() * 2f // 0-2 m/s
        }
    }
}