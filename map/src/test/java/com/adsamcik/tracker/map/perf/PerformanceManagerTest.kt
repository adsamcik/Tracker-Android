package com.adsamcik.tracker.map.perf

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceManagerTest {
    @Test
    fun `bucket thresholds`() {
        val pm = PerformanceManager()
        assertEquals(PerformanceManager.QualityBucket.LOW, pm.bucketForQuality(0.0f))
        assertEquals(PerformanceManager.QualityBucket.LOW, pm.bucketForQuality(0.74f))
        assertEquals(PerformanceManager.QualityBucket.MEDIUM, pm.bucketForQuality(0.75f))
        assertEquals(PerformanceManager.QualityBucket.MEDIUM, pm.bucketForQuality(0.99f))
        assertEquals(PerformanceManager.QualityBucket.HIGH, pm.bucketForQuality(1.0f))
        assertEquals(PerformanceManager.QualityBucket.HIGH, pm.bucketForQuality(5.0f))
    }

    @Test
    fun `budgets return nonzero limits`() {
        val pm = PerformanceManager()
        val b = pm.budgets(0.5f)
        assert(b.maxPoints > 0)
        assert(b.maxPolylinePoints > 0)
        assert(b.maxCacheSize > 0)
        assert(b.tileRenderTimeout >= 0)
        assert(b.batchSize > 0)
    }
}
