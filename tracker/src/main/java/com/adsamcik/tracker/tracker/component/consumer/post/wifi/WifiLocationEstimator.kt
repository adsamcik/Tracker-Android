package com.adsamcik.tracker.tracker.component.consumer.post.wifi

import com.adsamcik.tracker.shared.base.data.WifiData

/**
 * Aggregates multiple Wi-Fi scans into per-BSSID estimates.
 * Implementations are expected to be session scoped and not thread-safe by default.
 */
internal interface WifiLocationEstimator {
    /**
     * Registers a new Wi-Fi scan and returns the subset of BSSIDs whose aggregate changed
     * enough to warrant persistence.
     */
    fun onScan(wifiData: WifiData): List<WifiLocationEstimate>

    /** Snapshot of all current estimates without mutating internal state. */
    fun snapshot(): List<WifiLocationEstimate>

    /** Clears all cached estimates. */
    fun clear()

    /** Number of BSSIDs currently tracked. */
    val size: Int
}

internal data class WifiLocationEstimate(
    val bssid: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val maxRssi: Int,
    val ssid: String,
    val capabilities: String,
    val frequency: Int,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val sampleCount: Int,
    val estimatedErrorMeters: Double?
)
