package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Original Activity clock discontinuity is representable; unrelated reversed bounds are rejected. NOT_RUN. */
class MeasuredActivityTimeContractTest {
    @Test fun `original backward wall clock Activity band retains its exact times`() {
        val band = band()
        val row = row(2_000L, 1_000L, HistoryMeasurement.Activity(band))
        assertEquals(2_000L, row.position.startTimeMs)
        assertEquals(1_000L, row.toExclusiveMs)
        assertEquals(band, (row.measurement as HistoryMeasurement.Activity).band)
    }
    @Test fun `a reversed row cannot borrow discontinuity from a band with different times`() {
        assertFailsWith<IllegalArgumentException> { row(3_000L, 1_000L, HistoryMeasurement.Activity(band())) }
        assertFailsWith<IllegalArgumentException> { row(2_000L, 500L, HistoryMeasurement.Activity(band())) }
    }
    @Test fun `other measurements cannot accept reversed wall time windows`() {
        assertFailsWith<IllegalArgumentException> { row(2_000L, 1_000L, HistoryMeasurement.Gap(HistorySource.STEPS, "RESET")) }
        assertFailsWith<IllegalArgumentException> { row(2_000L, 1_000L, null) }
    }
    private fun row(start: Long, end: Long, measurement: HistoryMeasurement?) = UnifiedHistoryRow(
        HistoryPosition(start, "local:activity", "band"), end, setOf(HistoryOrigin.LOCAL),
        HistoryPurpose.CAPTURE, sections = emptyList(), measurement = measurement)
    private fun band() = ActivityHistoryFragment.Band("UTC", EpochMs(2_000L), EpochMs(1_000L), 0L, 0L,
        ActivityHistoryType.WALKING, ActivityHistoryMechanism.TRANSITION, null,
        ActivityHistoryConfidence.TransitionSignal, ActivityHistoryWallTimeContinuity.DISCONTINUITY_DETECTED, 500L)
}
