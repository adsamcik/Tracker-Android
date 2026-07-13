package com.adsamcik.tracker.tracker.component.producer

internal data class WifiFingerprintNetwork(
    val bssid: String,
    val frequency: Int,
    val levelDbm: Int,
    val capabilities: String,
)

/**
 * Pure decision logic for whether a Wi-Fi scan should be recorded.
 *
 * The Wi-Fi producer records scans both from fresh `SCAN_RESULTS_AVAILABLE` broadcasts and, as a
 * background-reliability fallback, from the system's cached results (because `startScan()` is
 * heavily throttled while the screen is off). The cached results can be observed on many cycles, so
 * this gate ensures each underlying scan is stored at most once and that long-stale results (e.g.
 * Wi-Fi left on hours ago with no new scan) are ignored.
 */
internal object WifiScanGate {

    /**
     * @param freshestTimestampMicros  freshest per-AP timestamp in the candidate scan
     *   (microseconds since boot, from [android.net.wifi.ScanResult.timestamp]).
     * @param lastRecordedTimestampMicros  freshest timestamp of the previously recorded scan, or
     *   [Long.MIN_VALUE] if none recorded yet.
     * @param nowElapsedRealtimeNanos  current monotonic clock (nanoseconds since boot).
     * @param maxAgeNanos  maximum age a scan may have and still be recorded.
     * @return true when the candidate is a not-yet-recorded scan that is still fresh enough.
     */
    fun shouldRecord(
        freshestTimestampMicros: Long,
        lastRecordedTimestampMicros: Long,
        nowElapsedRealtimeNanos: Long,
        maxAgeNanos: Long,
    ): Boolean {
        // Already recorded (or older than what we recorded) -> skip, avoids duplicate rows.
        if (freshestTimestampMicros <= lastRecordedTimestampMicros) return false
        // Reject scans older than the freshness window; negative age (clock rounding) counts as fresh.
        val scanAgeNanos = nowElapsedRealtimeNanos - freshestTimestampMicros * MICROS_TO_NANOS
        return scanAgeNanos <= maxAgeNanos
    }

    fun fingerprint(networks: List<WifiFingerprintNetwork>): String = networks
        .sortedWith(compareBy(WifiFingerprintNetwork::bssid, WifiFingerprintNetwork::frequency))
        .joinToString(separator = ";") { network ->
            val levelBucket = Math.floorDiv(network.levelDbm, RSSI_BUCKET_DB)
            network.bssid.lowercase() + "|" + network.frequency + "|" + levelBucket + "|" +
                network.capabilities
        }

    fun shouldRecordSnapshot(
        candidateFingerprint: String,
        previousFingerprint: String?,
        nowElapsedRealtimeNanos: Long,
        lastRecordedElapsedRealtimeNanos: Long,
        heartbeatNanos: Long,
    ): Boolean {
        if (candidateFingerprint.isEmpty()) return false
        if (candidateFingerprint != previousFingerprint) return true
        if (lastRecordedElapsedRealtimeNanos < 0L) return true
        return nowElapsedRealtimeNanos - lastRecordedElapsedRealtimeNanos >= heartbeatNanos
    }

    fun shouldBufferBroadcastResults(resultsUpdated: Boolean?): Boolean = resultsUpdated != false
    private const val RSSI_BUCKET_DB = 5

    private const val MICROS_TO_NANOS = 1_000L
}
