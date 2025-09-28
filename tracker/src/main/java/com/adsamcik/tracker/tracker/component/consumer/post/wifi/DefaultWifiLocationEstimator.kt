package com.adsamcik.tracker.tracker.component.consumer.post.wifi

import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.shared.base.data.WifiInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class DefaultWifiLocationEstimator(
    private val config: Config = Config()
) : WifiLocationEstimator {

    private val estimates = LinkedHashMap<String, WifiEstimateState>()

    override val size: Int
        get() = estimates.size

    override fun onScan(wifiData: WifiData): List<WifiLocationEstimate> {
        if (wifiData.inRange.isEmpty()) return emptyList()

        val location = wifiData.location
        val latitude = location?.latitude
        val longitude = location?.longitude
        val altitude = location?.altitude
        val accuracy = location?.horizontalAccuracy

        val updates = ArrayList<WifiLocationEstimate>()

        wifiData.inRange.forEach { info ->
            val state = stateFor(info, wifiData.time)
            val weightBefore = state.accumulatedWeight

            updateMetadata(state, info, wifiData.time)

            val canUseLocation = latitude != null && longitude != null && (accuracy == null || accuracy <= config.maxHorizontalAccuracyMeters)
            if (canUseLocation) {
                val rejected = shouldReject(state, latitude, longitude, info.level)
                if (!rejected) {
                    integrateLocation(state, latitude, longitude, altitude, info, wifiData.time)
                }
            }

            if (state.shouldPersist(wifiData.time, weightBefore != state.accumulatedWeight)) {
                updates.add(state.toEstimate(info.bssid, config))
                state.lastPersisted = wifiData.time
                state.dirty = false
            }
        }

        return updates
    }

    override fun snapshot(): List<WifiLocationEstimate> =
        estimates.map { (bssid, state) -> state.toEstimate(bssid, config) }

    override fun clear() {
        estimates.clear()
    }

    private fun stateFor(info: WifiInfo, time: Long): WifiEstimateState {
        val existing = estimates[info.bssid]
        if (existing != null) {
            existing.firstSeen = min(existing.firstSeen, time)
            return existing
        }

        ensureCapacity()
        return WifiEstimateState(
            ssid = info.ssid.orEmpty(),
            capabilities = info.capabilities,
            frequency = info.frequency,
            maxRssi = info.level,
            firstSeen = time,
            lastSeen = time
        ).also { estimates[info.bssid] = it }
    }

    private fun ensureCapacity() {
        if (estimates.size < config.maxEntries) return
        val toRemove = estimates.entries.minByOrNull { (_, state) -> state.lastSeen }
        if (toRemove != null) {
            estimates.remove(toRemove.key)
        }
    }

    private fun updateMetadata(state: WifiEstimateState, info: WifiInfo, time: Long) {
        state.lastSeen = max(state.lastSeen, time)
        if (!info.ssid.isNullOrBlank()) state.ssid = info.ssid!!
        state.capabilities = info.capabilities
        state.frequency = info.frequency
        if (info.level > state.maxRssi) state.maxRssi = info.level
    }

    private fun shouldReject(state: WifiEstimateState, lat: Double, lon: Double, rssi: Int): Boolean {
        if (state.latitude == null || state.longitude == null) return false

        val duplicate = state.recent.any {
            Location.distance(lat, lon, it.latitude, it.longitude, LengthUnit.Meter) <= config.duplicateRadiusMeters &&
                rssi <= it.rssi + config.duplicateRssiTolerance
        }
        if (duplicate) return true

        if (state.sampleCount < config.minSamplesForOutlierCheck) return false

        val distance = Location.distance(lat, lon, state.latitude!!, state.longitude!!, LengthUnit.Meter)
        val threshold = max(config.minOutlierRadiusMeters, state.spatialStdDev() * config.outlierStdDevMultiplier)
        return distance > threshold
    }

    private fun integrateLocation(
        state: WifiEstimateState,
        lat: Double,
        lon: Double,
        altitude: Double?,
        info: WifiInfo,
        time: Long
    ) {
        val weight = computeWeight(info.level, info.frequency)

        if (state.latitude == null || state.longitude == null) {
            state.latitude = lat
            state.longitude = lon
            state.altitude = altitude
            state.accumulatedWeight = weight
            state.sampleCount = 1
            state.meanDistance = 0.0
            state.distanceM2 = 0.0
            state.dirty = true
            state.recordRecent(lat, lon, info.level, time, config)
            return
        }

        val newWeightTotal = state.accumulatedWeight + weight
        val ratio = weight / newWeightTotal
        val newLat = state.latitude!! + ratio * (lat - state.latitude!!)
        val newLon = state.longitude!! + ratio * (lon - state.longitude!!)
        state.latitude = newLat
        state.longitude = newLon
        state.accumulatedWeight = newWeightTotal
        if (altitude != null) {
            state.altitude = when (val current = state.altitude) {
                null -> altitude
                else -> current + ratio * (altitude - current)
            }
        }

        state.sampleCount += 1
        val dist = Location.distance(newLat, newLon, lat, lon, LengthUnit.Meter)
        state.updateVariance(dist)
        state.dirty = true
        state.recordRecent(lat, lon, info.level, time, config)
    }

    private fun computeWeight(rssi: Int, frequency: Int): Double {
        val base = max(0.0, rssi + config.rssiOffsetForWeight.toDouble())
        val bandFactor = when {
            frequency >= 5925 -> config.sixGhzFactor
            frequency >= 5000 -> config.fiveGhzFactor
            else -> config.twoFourGhzFactor
        }
        return (base * bandFactor).coerceIn(config.minWeight, config.maxWeight)
    }

    private fun WifiEstimateState.updateVariance(distance: Double) {
        if (sampleCount <= 1) {
            meanDistance = 0.0
            distanceM2 = 0.0
            return
        }
        val delta = distance - meanDistance
        meanDistance += delta / sampleCount
        distanceM2 += delta * (distance - meanDistance)
    }

    private fun WifiEstimateState.spatialStdDev(): Double {
        if (sampleCount <= 1) return 0.0
        return sqrt(distanceM2 / (sampleCount - 1))
    }

    private fun WifiEstimateState.recordRecent(
        latitude: Double,
        longitude: Double,
        rssi: Int,
        time: Long,
        config: Config
    ) {
        recent.addLast(RecentSample(latitude, longitude, rssi, time))
        while (recent.size > config.recentSampleHistorySize) {
            recent.removeFirst()
        }
    }

    private fun WifiEstimateState.shouldPersist(time: Long, weightChanged: Boolean): Boolean {
        if (dirty) return true
        if (weightChanged) return true
        if (time - lastPersisted >= config.metadataUpdateIntervalMillis) return true
        return false
    }

    private fun WifiEstimateState.toEstimate(
        bssid: String,
        config: Config
    ): WifiLocationEstimate {
        val error = when {
            latitude == null || longitude == null -> null
            sampleCount <= 1 -> config.maxErrorMeters
            distanceM2 <= 0.0 -> config.minErrorMeters
            else -> sqrt(distanceM2 / (sampleCount - 1)).coerceIn(config.minErrorMeters, config.maxErrorMeters)
        }
        return WifiLocationEstimate(
            bssid = bssid,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            maxRssi = maxRssi,
            ssid = ssid,
            capabilities = capabilities,
            frequency = frequency,
            firstSeenMillis = firstSeen,
            lastSeenMillis = lastSeen,
            sampleCount = sampleCount,
            estimatedErrorMeters = error
        )
    }

    internal data class Config(
        val maxEntries: Int = 5_000,
        val duplicateRadiusMeters: Double = 5.0,
        val duplicateRssiTolerance: Int = 1,
        val maxHorizontalAccuracyMeters: Float = 30f,
        val minSamplesForOutlierCheck: Int = 3,
        val minOutlierRadiusMeters: Double = 25.0,
        val outlierStdDevMultiplier: Double = 2.5,
        val metadataUpdateIntervalMillis: Long = 60_000L,
        val minWeight: Double = 1.0,
        val maxWeight: Double = 120.0,
        val rssiOffsetForWeight: Int = 110,
        val twoFourGhzFactor: Double = 1.0,
        val fiveGhzFactor: Double = 0.85,
        val sixGhzFactor: Double = 0.7,
        val recentSampleHistorySize: Int = 8,
        val minErrorMeters: Double = 5.0,
        val maxErrorMeters: Double = 250.0
    )

    private data class WifiEstimateState(
        var latitude: Double? = null,
        var longitude: Double? = null,
        var altitude: Double? = null,
        var accumulatedWeight: Double = 0.0,
        var sampleCount: Int = 0,
        var meanDistance: Double = 0.0,
        var distanceM2: Double = 0.0,
        var maxRssi: Int,
        var ssid: String,
        var capabilities: String,
        var frequency: Int,
        var firstSeen: Long,
        var lastSeen: Long,
        var lastPersisted: Long = 0L,
        val recent: ArrayDeque<RecentSample> = ArrayDeque(),
        var dirty: Boolean = true
    )

    private data class RecentSample(
        val latitude: Double,
        val longitude: Double,
        val rssi: Int,
        val time: Long
    )
}
