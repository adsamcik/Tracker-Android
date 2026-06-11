package com.adsamcik.tracker.impexp.exporter.automation

import com.adsamcik.tracker.shared.base.time.Clock
import java.time.Duration
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

private class FixedClock(private var now: Long) : Clock {
    override fun currentTimeMillis(): Long = now
    override fun elapsedRealtimeNanos(): Long = now * 1_000_000L
    fun advance(millis: Long) { now += millis }
}

class ExportPlanSchedulingTest {

    @Test
    fun repeatDuration_forWeeklyPlan() {
        val cadence = ExportCadence.Interval(
            unit = ExportCadence.IntervalUnit.WEEK,
            every = 2
        )
        assertEquals(Duration.ofDays(14), ExportPlanScheduling.repeatDuration(cadence))
    }

    @Test
    fun initialDelay_respectsTimeOfDay() {
        val cadence = ExportCadence.Interval(
            unit = ExportCadence.IntervalUnit.DAY,
            every = 1,
            atTime = java.time.LocalTime.of(6, 0)
        )
        val clock = FixedClock(/* 2025-11-22T05:00:00Z */ 1763787600000L)
        val delay = ExportPlanScheduling.initialDelay(
            cadence = cadence,
            clock = clock,
            nowMillis = clock.currentTimeMillis(),
            zoneId = ZoneId.of("UTC")
        )
        assertEquals(Duration.ofHours(1), delay)
    }
}
