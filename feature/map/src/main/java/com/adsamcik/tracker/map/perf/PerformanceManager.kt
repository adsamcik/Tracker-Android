package com.adsamcik.tracker.map.perf

/**
 * Centralized performance budgets based on map quality level.
 * Quality in v1 is expressed as a Float (0f..1f+). We coarsely bucket it to LOW / MEDIUM / HIGH
 * so future tuning is easier and central.
 */
class PerformanceManager {

    /** Quality buckets. */
    enum class QualityBucket { LOW, MEDIUM, HIGH }

    data class PerformanceBudgets(
        val maxPoints: Int,
        val maxPolylinePoints: Int,
        val maxCacheSize: Int,
        val tileRenderTimeout: Long,
        val decimationThreshold: Int,
        val batchSize: Int,
    )

    private val low = PerformanceBudgets(
        maxPoints = 15_000,
        maxPolylinePoints = 2_500,
        maxCacheSize = 48,
        tileRenderTimeout = 2_500,
        decimationThreshold = 8_000,
        batchSize = 256,
    )
    private val medium = PerformanceBudgets(
        maxPoints = 40_000,
        maxPolylinePoints = 6_000,
        maxCacheSize = 72,
        tileRenderTimeout = 3_000,
        decimationThreshold = 15_000,
        batchSize = 384,
    )
    private val high = PerformanceBudgets(
        maxPoints = 80_000,
        maxPolylinePoints = 12_000,
        maxCacheSize = 96,
        tileRenderTimeout = 3_500,
        decimationThreshold = 30_000,
        batchSize = 512,
    )

    fun bucketForQuality(raw: Float): QualityBucket = when {
        raw < 0.75f -> QualityBucket.LOW
        raw < 1.0f -> QualityBucket.MEDIUM
        else -> QualityBucket.HIGH
    }

    fun budgets(rawQuality: Float): PerformanceBudgets = when (bucketForQuality(rawQuality)) {
        QualityBucket.LOW -> low
        QualityBucket.MEDIUM -> medium
        QualityBucket.HIGH -> high
    }

    /**
     * Overload accepting zoom level for future zoom-aware budget tuning.
     * Currently delegates to the quality-only variant; zoom is reserved for later use.
     */
    fun budgets(rawQuality: Float, zoom: Float): PerformanceBudgets = budgets(rawQuality)
}
