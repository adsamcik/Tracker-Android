package com.adsamcik.tracker.tracker.component.producer

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WifiScanGate")
class WifiScanGateTest {

    private val maxAge = 2L * 60 * 1_000_000_000 // 2 minutes in nanos
    private val now = 10_000_000_000L // 10s since boot, in nanos

    @Test
    @DisplayName("records a new, fresh scan")
    fun recordsNewFreshScan() {
        // freshest 9s since boot (micros) -> 1s old, well within the window
        WifiScanGate.shouldRecord(
            freshestTimestampMicros = 9_000_000,
            lastRecordedTimestampMicros = Long.MIN_VALUE,
            nowElapsedRealtimeNanos = now,
            maxAgeNanos = maxAge,
        ) shouldBe true
    }

    @Test
    @DisplayName("skips a scan already recorded (same timestamp)")
    fun skipsAlreadyRecorded() {
        WifiScanGate.shouldRecord(
            freshestTimestampMicros = 9_000_000,
            lastRecordedTimestampMicros = 9_000_000,
            nowElapsedRealtimeNanos = now,
            maxAgeNanos = maxAge,
        ) shouldBe false
    }

    @Test
    @DisplayName("skips a scan older than the last recorded one")
    fun skipsOlderThanLast() {
        WifiScanGate.shouldRecord(
            freshestTimestampMicros = 8_000_000,
            lastRecordedTimestampMicros = 9_000_000,
            nowElapsedRealtimeNanos = now,
            maxAgeNanos = maxAge,
        ) shouldBe false
    }

    @Test
    @DisplayName("skips a brand-new but stale cached scan")
    fun skipsStaleScan() {
        // freshest at 1s since boot -> 9s old at now=10s; with a 5s window it is too stale
        WifiScanGate.shouldRecord(
            freshestTimestampMicros = 1_000_000,
            lastRecordedTimestampMicros = Long.MIN_VALUE,
            nowElapsedRealtimeNanos = now,
            maxAgeNanos = 5L * 1_000_000_000,
        ) shouldBe false
    }

    @Test
    @DisplayName("records a scan whose timestamp is essentially 'now' (zero/negative age)")
    fun recordsZeroAgeScan() {
        WifiScanGate.shouldRecord(
            freshestTimestampMicros = now / 1_000,
            lastRecordedTimestampMicros = Long.MIN_VALUE,
            nowElapsedRealtimeNanos = now,
            maxAgeNanos = maxAge,
        ) shouldBe true
    }
}
