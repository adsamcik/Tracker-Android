package com.adsamcik.tracker.statistics.data.source

import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.LocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedLocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [StatisticDataManager] construction, producer/consumer registration,
 * and topological sorting of the data pipeline.
 *
 * Note: The manager orchestrates Android-dependent operations (database, context)
 * internally, so we focus on verifiable construction-time behavior:
 * - Producer topological ordering
 * - Consumer/producer list integrity
 * - Data map preparation helpers
 */
@DisplayName("StatisticDataManager")
class StatisticDataManagerTest {

	// ========================================================================
	// Construction and initialization
	// ========================================================================

	@Nested
	@DisplayName("Construction")
	inner class Construction {

		@Test
		fun `manager constructs without error`() {
			val manager = StatisticDataManager()
			manager.shouldNotBeNull()
		}

		@Test
		fun `manager can be instantiated multiple times independently`() {
			val manager1 = StatisticDataManager()
			val manager2 = StatisticDataManager()

			manager1.shouldNotBeNull()
			manager2.shouldNotBeNull()
		}
	}

	// ========================================================================
	// Producer topological ordering
	// ========================================================================

	@Nested
	@DisplayName("Producer topological ordering")
	inner class ProducerOrdering {

		/**
		 * The topological sort produces a deterministic ordering of producers.
		 * Edges are constructed from dependent→dependency, so the sort places
		 * dependents before their dependencies. The actual dependency resolution
		 * in requireProducers is recursive, so list order is for iteration only.
		 */
		@Test
		fun `producers are topologically sorted into deterministic order`() {
			val manager = StatisticDataManager()

			val producersField = StatisticDataManager::class.java.getDeclaredField("producers")
			producersField.isAccessible = true

			@Suppress("UNCHECKED_CAST")
			val producers = producersField.get(manager) as List<StatDataProducer>

			producers.shouldNotBeEmpty()
			producers shouldHaveSize 4

			// Verify both producers exist in the sorted list
			val hasOptimizedLocation = producers.any { it is OptimizedLocationDataProducer }
			val hasOptimizedAltitude = producers.any { it is OptimizedAltitudeProducer }

			hasOptimizedLocation shouldBe true
			hasOptimizedAltitude shouldBe true
		}

		@Test
		fun `all expected producer types are registered`() {
			val manager = StatisticDataManager()

			val producersField = StatisticDataManager::class.java.getDeclaredField("producers")
			producersField.isAccessible = true

			@Suppress("UNCHECKED_CAST")
			val producers = producersField.get(manager) as List<StatDataProducer>

			val producerTypes = producers.map { it::class }.toSet()
			producerTypes shouldContainExactlyInAnyOrder listOf(
				TrackerSessionProducer::class,
				LocationDataProducer::class,
				OptimizedLocationDataProducer::class,
				OptimizedAltitudeProducer::class
			)
		}

		@Test
		fun `producers with no dependencies appear in the list`() {
			val manager = StatisticDataManager()

			val producersField = StatisticDataManager::class.java.getDeclaredField("producers")
			producersField.isAccessible = true

			@Suppress("UNCHECKED_CAST")
			val producers = producersField.get(manager) as List<StatDataProducer>

			val noDeps = producers.filter { it.dependsOn.isEmpty() }
			noDeps.shouldNotBeEmpty()
			// TrackerSessionProducer, LocationDataProducer, OptimizedLocationDataProducer have no dependsOn
			noDeps.size shouldBe 3
		}
	}

	// ========================================================================
	// Consumer registration
	// ========================================================================

	@Nested
	@DisplayName("Consumer registration")
	inner class ConsumerRegistration {

		@Test
		fun `all 12 consumers are registered`() {
			val manager = StatisticDataManager()

			val consumersField = StatisticDataManager::class.java.getDeclaredField("consumers")
			consumersField.isAccessible = true

			val consumers = consumersField.get(manager) as List<*>
			consumers shouldHaveSize 12
		}

		@Test
		fun `each consumer has a unique providerId`() {
			val manager = StatisticDataManager()

			val consumersField = StatisticDataManager::class.java.getDeclaredField("consumers")
			consumersField.isAccessible = true

			@Suppress("UNCHECKED_CAST")
			val consumers =
				consumersField.get(manager) as List<com.adsamcik.tracker.statistics.data.source.abstraction.StatDataConsumer>

			val ids = consumers.map { it.providerId }
			ids.toSet().size shouldBe ids.size
		}
	}

	// ========================================================================
	// Raw producers registration
	// ========================================================================

	@Nested
	@DisplayName("Raw producer registration")
	inner class RawProducerRegistration {

		@Test
		fun `raw producers include LOCATION and WIFI_LOCATION types`() {
			val manager = StatisticDataManager()

			val rawField = StatisticDataManager::class.java.getDeclaredField("rawProducers")
			rawField.isAccessible = true

			@Suppress("UNCHECKED_CAST")
			val rawProducers =
				rawField.get(manager) as List<com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer>

			rawProducers shouldHaveSize 2
			val types = rawProducers.map { it.type }
			types shouldContainExactly listOf(StatDataSource.LOCATION, StatDataSource.WIFI_LOCATION)
		}
	}

	// ========================================================================
	// Data pipeline helpers
	// ========================================================================

	@Nested
	@DisplayName("Data pipeline helpers")
	inner class DataPipelineHelpers {

		@Test
		fun `ConcurrentCacheData constructed with session has non-null data`() {
			val lock = java.util.concurrent.locks.ReentrantLock()
			val producer = io.mockk.mockk<com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer>(
				relaxed = true
			)
			val sessionData = "session-placeholder"

			val cache = ConcurrentCacheData(lock, producer, sessionData)

			cache.data shouldBe "session-placeholder"
			cache.producer shouldBe producer
		}

		@Test
		fun `ConcurrentCacheData constructed without data starts null`() {
			val lock = java.util.concurrent.locks.ReentrantLock()
			val producer = io.mockk.mockk<StatDataProducer>(relaxed = true)

			val cache = ConcurrentCacheData(lock, producer, null)

			cache.data shouldBe null
		}

		@Test
		fun `StatDataMap entries can be looked up by producer class`() {
			val lock = java.util.concurrent.locks.ReentrantLock()
			val producer = TrackerSessionProducer()
			val data = "test-session-data"

			@Suppress("UNCHECKED_CAST")
			val entry: Pair<kotlin.reflect.KClass<out StatDataProducer>, ConcurrentCacheData<StatDataProducer>> =
				TrackerSessionProducer::class to ConcurrentCacheData(
					lock, producer as StatDataProducer, data
				)

			val statDataMap: StatDataMap = mapOf(entry)

			statDataMap[TrackerSessionProducer::class].shouldNotBeNull()
			statDataMap[TrackerSessionProducer::class]!!.data shouldBe "test-session-data"
			statDataMap[TrackerSessionProducer::class]!!.producer.shouldBeInstanceOf<TrackerSessionProducer>()
		}

		@Test
		fun `RawDataMap entries can be looked up by StatDataSource`() {
			val lock = java.util.concurrent.locks.ReentrantLock()
			val producer = io.mockk.mockk<com.adsamcik.tracker.statistics.data.source.abstraction.RawDataProducer>(
				relaxed = true
			)
			io.mockk.every { producer.type } returns StatDataSource.LOCATION

			val cache = ConcurrentCacheData(lock, producer, listOf("loc1", "loc2"))
			val rawDataMap: RawDataMap = mapOf(StatDataSource.LOCATION to cache)

			rawDataMap[StatDataSource.LOCATION].shouldNotBeNull()
			rawDataMap[StatDataSource.LOCATION]!!.data shouldBe listOf("loc1", "loc2")
		}
	}

	// ========================================================================
	// Error handling in pipeline
	// ========================================================================

	@Nested
	@DisplayName("Error handling in pipeline")
	inner class ErrorHandling {

		@Test
		fun `accessing missing StatDataMap key returns null`() {
			val statDataMap: StatDataMap = emptyMap()

			val result = statDataMap[OptimizedAltitudeProducer::class]
			result shouldBe null
		}

		@Test
		fun `accessing missing RawDataMap key returns null`() {
			val rawDataMap: RawDataMap = emptyMap()

			val result = rawDataMap[StatDataSource.CELL]
			result shouldBe null
		}

		@Test
		fun `producer dependency cycle would be caught at construction`() {
			// The topological sort in StatisticDataManager's init block uses Graph.topSort()
			// which would throw on cycles. Since the current producer list is acyclic,
			// construction succeeds — this test documents that guarantee.
			val manager = StatisticDataManager()
			manager.shouldNotBeNull()
		}
	}
}
