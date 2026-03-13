package com.adsamcik.tracker.map.tiles

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TileCacheTest {

    private lateinit var cache: TileCache

    @BeforeEach
    fun setup() {
        cache = TileCache(maxSize = 4)
    }

    @Nested
    inner class KeyGeneration {
        @Test
        fun `key format is layerId-z-x-y`() {
            cache.key("heatmap", 10, 512, 340) shouldBe "heatmap/10/512/340"
        }
    }

    @Nested
    inner class GetAndPut {
        @Test
        fun `get returns null for missing key`() {
            cache.get("missing/1/2/3").shouldBeNull()
        }

        @Test
        fun `put then get returns stored value`() {
            cache.put("layer/1/0/0", """{"type":"FeatureCollection","features":[]}""")
            cache.get("layer/1/0/0").shouldNotBeNull()
            cache.get("layer/1/0/0")!! shouldContain "FeatureCollection"
        }

        @Test
        fun `overwrite updates value`() {
            cache.put("layer/1/0/0", "first")
            cache.put("layer/1/0/0", "second")
            cache.get("layer/1/0/0") shouldBe "second"
            cache.size() shouldBe 1
        }
    }

    @Nested
    inner class LruEviction {
        @Test
        fun `evicts oldest entry when max size exceeded`() {
            cache.put("a/1/0/0", "a")
            cache.put("b/1/0/0", "b")
            cache.put("c/1/0/0", "c")
            cache.put("d/1/0/0", "d")

            // Cache is full (4 items). Adding one more should evict "a"
            cache.put("e/1/0/0", "e")

            cache.get("a/1/0/0").shouldBeNull()
            cache.get("e/1/0/0") shouldBe "e"
            cache.size() shouldBe 4
        }

        @Test
        fun `recently accessed entry survives eviction`() {
            cache.put("a/1/0/0", "a")
            cache.put("b/1/0/0", "b")
            cache.put("c/1/0/0", "c")
            cache.put("d/1/0/0", "d")

            // Access "a" to make it recently used
            cache.get("a/1/0/0")

            // Adding a new entry should evict "b" (least recently used), not "a"
            cache.put("e/1/0/0", "e")

            cache.get("a/1/0/0") shouldBe "a"
            cache.get("b/1/0/0").shouldBeNull()
        }
    }

    @Nested
    inner class Invalidation {
        @Test
        fun `invalidateLayer removes only matching entries`() {
            cache.put("heatmap/10/0/0", "h1")
            cache.put("heatmap/10/0/1", "h2")
            cache.put("polyline/10/0/0", "p1")

            cache.invalidateLayer("heatmap")

            cache.get("heatmap/10/0/0").shouldBeNull()
            cache.get("heatmap/10/0/1").shouldBeNull()
            cache.get("polyline/10/0/0") shouldBe "p1"
        }

        @Test
        fun `clear removes all entries`() {
            cache.put("a/1/0/0", "a")
            cache.put("b/1/0/0", "b")

            cache.clear()

            cache.size() shouldBe 0
            cache.get("a/1/0/0").shouldBeNull()
        }
    }

    @Nested
    inner class ThreadSafety {
        @Test
        fun `concurrent reads and writes do not crash`() {
            val bigCache = TileCache(maxSize = 100)
            val threads = (0 until 10).map { threadIdx ->
                Thread {
                    repeat(100) { i ->
                        bigCache.put("t$threadIdx/$i/0/0", "data-$threadIdx-$i")
                        bigCache.get("t$threadIdx/$i/0/0")
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            // No ConcurrentModificationException means we pass
            bigCache.size() shouldBe 100
        }
    }
}
