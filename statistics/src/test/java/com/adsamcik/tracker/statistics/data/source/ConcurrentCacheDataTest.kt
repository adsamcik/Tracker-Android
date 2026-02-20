package com.adsamcik.tracker.statistics.data.source

import com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@DisplayName("ConcurrentCacheData")
class ConcurrentCacheDataTest {

	private fun <T> createCache(
		producer: T,
		initialData: Any? = null
	): ConcurrentCacheData<T> {
		return ConcurrentCacheData(
			lock = ReentrantLock(),
			producer = producer,
			data = initialData
		)
	}

	// ========================================================================
	// Cache hit / miss
	// ========================================================================

	@Nested
	@DisplayName("Cache hit and miss")
	inner class CacheHitMiss {

		@Test
		fun `data is null when created without initial data`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer)

			cache.data.shouldBeNull()
		}

		@Test
		fun `data returns initial value when provided`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val data = listOf(1, 2, 3)
			val cache = createCache(producer, data)

			cache.data shouldBe listOf(1, 2, 3)
		}

		@Test
		fun `data returns same instance that was stored`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val data = mutableListOf("a", "b")
			val cache = createCache(producer, data)

			cache.data shouldBeSameInstanceAs data
		}

		@Test
		fun `cache miss then populate returns populated data`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer)

			cache.data.shouldBeNull()

			val produced = "computed-result"
			cache.lock.withLock {
				if (cache.data == null) {
					cache.data = produced
				}
			}

			cache.data shouldBe "computed-result"
		}
	}

	// ========================================================================
	// Cache invalidation
	// ========================================================================

	@Nested
	@DisplayName("Cache invalidation")
	inner class CacheInvalidation {

		@Test
		fun `setting data to null invalidates cache`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, "initial-data")

			cache.data shouldBe "initial-data"

			cache.lock.withLock {
				cache.data = null
			}

			cache.data.shouldBeNull()
		}

		@Test
		fun `overwriting data replaces previous value`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, "old")

			cache.lock.withLock {
				cache.data = "new"
			}

			cache.data shouldBe "new"
		}

		@Test
		fun `invalidate and re-populate works correctly`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, "first")

			cache.lock.withLock { cache.data = null }
			cache.data.shouldBeNull()

			cache.lock.withLock { cache.data = "second" }
			cache.data shouldBe "second"
		}
	}

	// ========================================================================
	// Producer reference
	// ========================================================================

	@Nested
	@DisplayName("Producer reference")
	inner class ProducerReference {

		@Test
		fun `producer is accessible after construction`() {
			val producer = mockk<RawDataProducer>(relaxed = true)
			every { producer.type } returns StatDataSource.LOCATION
			val cache = createCache(producer)

			cache.producer shouldBeSameInstanceAs producer
			cache.producer.type shouldBe StatDataSource.LOCATION
		}

		@Test
		fun `producer stays consistent regardless of data state`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, "data")

			val producerBefore = cache.producer
			cache.data = null
			val producerAfter = cache.producer

			producerBefore shouldBeSameInstanceAs producerAfter
		}
	}

	// ========================================================================
	// Concurrent access safety
	// ========================================================================

	@Nested
	@DisplayName("Concurrent access safety")
	inner class ConcurrentAccess {

		@Test
		fun `lock prevents concurrent modification`() = runTest {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, 0)

			val iterations = 1000
			val jobs = (0 until iterations).map {
				launch(Dispatchers.Default) {
					cache.lock.withLock {
						val current = cache.data as Int
						cache.data = current + 1
					}
				}
			}
			jobs.joinAll()

			cache.data shouldBe iterations
		}

		@Test
		fun `lazy initialization under lock only produces once`() = runTest {
			var produceCount = 0
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer)

			val jobs = (0 until 50).map {
				launch(Dispatchers.Default) {
					cache.lock.withLock {
						if (cache.data == null) {
							produceCount++
							cache.data = "result"
						}
					}
				}
			}
			jobs.joinAll()

			produceCount shouldBe 1
			cache.data shouldBe "result"
		}

		@Test
		fun `concurrent reads return consistent data`() = runTest {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val expected = listOf(1, 2, 3, 4, 5)
			val cache = createCache(producer, expected)

			val results = mutableListOf<Any?>()
			val jobs = (0 until 100).map {
				launch(Dispatchers.Default) {
					val data = cache.lock.withLock { cache.data }
					synchronized(results) {
						results.add(data)
					}
				}
			}
			jobs.joinAll()

			results.forEach { it shouldBe expected }
		}
	}

	// ========================================================================
	// Memory cleanup
	// ========================================================================

	@Nested
	@DisplayName("Memory cleanup")
	inner class MemoryCleanup {

		@Test
		fun `setting data to null releases reference`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val largeData = ByteArray(1024) { it.toByte() }
			val cache = createCache(producer, largeData)

			cache.data shouldBe largeData

			cache.lock.withLock {
				cache.data = null
			}

			cache.data.shouldBeNull()
		}

		@Test
		fun `replacing data releases old reference`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val oldData = "old-large-object"
			val newData = "new-small-object"
			val cache = createCache(producer, oldData)

			cache.lock.withLock {
				cache.data = newData
			}

			cache.data shouldBe newData
		}

		@Test
		fun `data class copy creates independent instance`() {
			val producer = mockk<StatDataProducer>(relaxed = true)
			val cache = createCache(producer, "original")

			val copy = cache.copy(data = "modified")

			cache.data shouldBe "original"
			copy.data shouldBe "modified"
			cache.producer shouldBeSameInstanceAs copy.producer
		}
	}
}
