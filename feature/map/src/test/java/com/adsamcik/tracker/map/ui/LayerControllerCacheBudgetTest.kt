package com.adsamcik.tracker.map.ui

import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Regression test for the heatmap viewport cache memory leak documented in
 * R2 round 5 (Phase 11) finding #3.
 *
 * Before the fix, [LayerController]'s cache held up to 5 [MapLibreLayerConfig.Heatmap]
 * payloads per layer. Each dense (~80k point) heatmap encodes to ~12 MB UTF-16, so
 * five such buckets retained ~60 MB per layer and ~300 MB across the 5 active
 * heatmap layers (location, cell, speed, wifi count, wifi).
 *
 * The fix replaces the bucket-count cap with a byte-size budget per layer via
 * [ViewportConfigCache]. This test exercises 20 distinct bucket keys against that
 * budget and asserts both that retention stays bounded and that recently-used
 * entries survive eviction (so rapid back-and-forth pan within a single viewport
 * still skips the GeoJSON re-encode).
 *
 * Sizes are scaled down (KB instead of MB) to keep the unit-test JVM heap quiet
 * — the cache invariants are size-independent, so the assertions hold regardless
 * of absolute payload size.
 */
@DisplayName("LayerController viewport cache budget")
class LayerControllerCacheBudgetTest {

    private fun bucketKey(seed: Int): LayerController.ViewportCacheKey =
        LayerController.ViewportCacheKey(
            bounds = LayerController.BucketedBounds(
                north = seed.toLong(),
                east = seed.toLong(),
                south = (seed - 1).toLong(),
                west = (seed - 1).toLong(),
            ),
            zoom = 12,
            dateFromMs = 0L,
            dateToMs = Long.MAX_VALUE,
            qualityBucket = 100,
        )

    /** Build a heatmap whose UTF-16 byte size is roughly [approxKb] KB. */
    private fun heatmapOfKb(approxKb: Int): MapLibreLayerConfig.Heatmap {
        // UTF-16: 512 chars = 1024 bytes = 1 KB.
        val chars = approxKb * 512
        return MapLibreLayerConfig.Heatmap(
            geoJson = "x".repeat(chars),
            colorStops = emptyList(),
        )
    }

    @Nested
    @DisplayName("byte budget")
    inner class ByteBudget {

        @Test
        fun `cache size never exceeds budget across 20 distinct buckets`() {
            val budgetBytes = 16L * 1024L // 16 KB scaled-down stand-in for 16 MB.
            val cache = ViewportConfigCache(maxBytes = budgetBytes)
            val payloadKb = 12
            val payloadBytes = payloadKb.toLong() * 1024L
            // Each payload fits inside the budget on its own, so after eviction the
            // cache must stay strictly within the budget after every insert.
            check(payloadBytes <= budgetBytes)

            repeat(20) { i ->
                cache.put(bucketKey(i), heatmapOfKb(approxKb = payloadKb))
                cache.byteSize() shouldBeLessThanOrEqualTo budgetBytes
            }
        }

        @Test
        fun `cache strictly fits within budget when entries fit individually`() {
            val budgetBytes = 16L * 1024L
            val cache = ViewportConfigCache(maxBytes = budgetBytes)

            // 4 KB payloads: budget holds ~4 entries before eviction starts.
            repeat(20) { i ->
                cache.put(bucketKey(i), heatmapOfKb(approxKb = 4))
            }

            cache.byteSize() shouldBeLessThanOrEqualTo budgetBytes
            (cache.size() <= 5).shouldBeTrue()
        }

        @Test
        fun `single oversize entry is retained even when larger than budget`() {
            val cache = ViewportConfigCache(maxBytes = 4L * 1024L)
            cache.put(bucketKey(0), heatmapOfKb(approxKb = 12))

            // Original perf intent (skip re-encode on rapid back-and-forth pan)
            // requires the most recent entry survive even when oversize.
            cache.size() shouldBe 1
            cache.get(bucketKey(0)).shouldNotBeNull()
        }
    }

    @Nested
    @DisplayName("MRU retention")
    inner class MruRetention {

        @Test
        fun `most recently used entries are retained, oldest are evicted`() {
            val cache = ViewportConfigCache(maxBytes = 16L * 1024L)

            // Insert 20 sizable payloads (~5 KB each). Only the last few fit.
            repeat(20) { i ->
                cache.put(bucketKey(i), heatmapOfKb(approxKb = 5))
            }

            // The very latest insert must always be present (it is the protected MRU).
            cache.containsKey(bucketKey(19)).shouldBeTrue()
            cache.get(bucketKey(19)).shouldNotBeNull()

            // The earliest insert must have been evicted long ago.
            cache.containsKey(bucketKey(0)) shouldBe false
            cache.get(bucketKey(0)).shouldBeNull()
        }

        @Test
        fun `reading an entry promotes it so it survives later eviction`() {
            val cache = ViewportConfigCache(maxBytes = 20L * 1024L)

            // Fill near budget with 4 KB payloads plus the fixed per-entry charge (4 fit).
            repeat(4) { i ->
                cache.put(bucketKey(i), heatmapOfKb(approxKb = 4))
            }

            // Promote bucket 0 by reading it.
            cache.get(bucketKey(0)).shouldNotBeNull()

            // Insert two new payloads. Pre-promotion bucket 0 would be evicted;
            // post-promotion bucket 1 must be evicted instead.
            cache.put(bucketKey(100), heatmapOfKb(approxKb = 4))
            cache.put(bucketKey(101), heatmapOfKb(approxKb = 4))

            cache.containsKey(bucketKey(0)).shouldBeTrue()
            cache.containsKey(bucketKey(1)) shouldBe false
        }

        @Test
        fun `null payload entries are cached with an entry overhead charge`() {
            val cache = ViewportConfigCache(maxBytes = 4L * 1024L)

            cache.put(bucketKey(0), null)
            cache.put(bucketKey(1), null)

            cache.byteSize() shouldBe ViewportConfigCache.ENTRY_OVERHEAD_BYTES * 2
            cache.containsKey(bucketKey(0)).shouldBeTrue()
            cache.containsKey(bucketKey(1)).shouldBeTrue()
            cache.size() shouldBe 2
        }

        @Test
        fun `distinct null entries cannot grow beyond the entry limit`() {
            val cache = ViewportConfigCache(maxBytes = 16L * 1024L, maxEntries = 3)

            repeat(100) { cache.put(bucketKey(it), null) }

            cache.size() shouldBe 3
            cache.containsKey(bucketKey(99)).shouldBeTrue()
            cache.byteSize() shouldBe ViewportConfigCache.ENTRY_OVERHEAD_BYTES * 3
        }

        @Test
        fun `entry count limit applies even when byte budget has room`() {
            val cache = ViewportConfigCache(maxBytes = 16L * 1024L, maxEntries = 3)

            repeat(10) { cache.put(bucketKey(it), MapLibreLayerConfig.Line(geoJson = "x", colorArgb = 0)) }

            cache.size() shouldBe 3
            cache.byteSize() shouldBeLessThanOrEqualTo 16L * 1024L
            cache.containsKey(bucketKey(9)).shouldBeTrue()
        }
    }

    @Nested
    @DisplayName("composite accounting")
    inner class CompositeAccounting {

        @Test
        fun `composite layer sums child byte sizes`() {
            val child1 = heatmapOfKb(approxKb = 2)
            val child2 = MapLibreLayerConfig.Line(
                geoJson = "y".repeat(1024),
                colorArgb = 0,
            )
            val composite = MapLibreLayerConfig.Composite(listOf(child1, child2))

            ViewportConfigCache.estimateBytes(composite) shouldBe
                ViewportConfigCache.ENTRY_OVERHEAD_BYTES +
                (child1.geoJson.length.toLong() * 2L) +
                (child2.geoJson.length.toLong() * 2L)
        }

        @Test
        fun `null estimate includes entry overhead`() {
            ViewportConfigCache.estimateBytes(null) shouldBe ViewportConfigCache.ENTRY_OVERHEAD_BYTES
        }
    }
}
