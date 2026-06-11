package com.adsamcik.tracker.map.perf

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PerformanceManagerTest {
    @Test
    fun `bucket thresholds`() {
        val pm = PerformanceManager()
        pm.bucketForQuality(0.0f) shouldBe PerformanceManager.QualityBucket.LOW
        pm.bucketForQuality(0.74f) shouldBe PerformanceManager.QualityBucket.LOW
        pm.bucketForQuality(0.75f) shouldBe PerformanceManager.QualityBucket.MEDIUM
        pm.bucketForQuality(0.99f) shouldBe PerformanceManager.QualityBucket.MEDIUM
        pm.bucketForQuality(1.0f) shouldBe PerformanceManager.QualityBucket.HIGH
        pm.bucketForQuality(5.0f) shouldBe PerformanceManager.QualityBucket.HIGH
    }

    @Test
    fun `budgets return nonzero limits`() {
        val pm = PerformanceManager()
        val b = pm.budgets(0.5f)
        (b.maxPoints > 0) shouldBe true
        (b.maxPolylinePoints > 0) shouldBe true
        (b.maxCacheSize > 0) shouldBe true
        (b.tileRenderTimeout >= 0) shouldBe true
        (b.batchSize > 0) shouldBe true
    }
}
