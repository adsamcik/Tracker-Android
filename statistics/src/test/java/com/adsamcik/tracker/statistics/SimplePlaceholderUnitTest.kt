package com.adsamcik.tracker.statistics

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.statistics.data.LocationExtractor
import com.adsamcik.tracker.statistics.data.source.ConcurrentCacheData
import com.adsamcik.tracker.statistics.data.source.MutableMultiTypeMap
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.consumer.AscensionConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.AvgSpeedConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.DescensionConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MaxAltitudeConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MaxSpeedConsumer
import com.adsamcik.tracker.statistics.data.source.consumer.MinAltitudeConsumer
import com.adsamcik.tracker.statistics.data.source.producer.LocationDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import io.kotest.assertions.throwables.shouldThrow
import java.util.concurrent.locks.ReentrantLock
import kotlin.reflect.KClass

/**
 * Unit tests for statistics module business logic.
 *
 * Tests cover:
 * - MutableMultiTypeMap typed data structure
 * - LocationExtractor coordinate extraction
 * - Altitude consumers (ascension, descension, max, min)
 * - Speed consumers (max, average)
 */
@DisplayName("Statistics Business Logic")
class SimplePlaceholderUnitTest {

	// -- Helpers for constructing StatDataMap with test data --

	/**
	 * Builds a [StatDataMap] entry mapping a producer class to cached data.
	 */
	private fun <P : StatDataProducer> statEntry(
		producerClass: KClass<P>,
		producer: P,
		data: Any
	): Pair<KClass<out StatDataProducer>, ConcurrentCacheData<StatDataProducer>> {
		@Suppress("UNCHECKED_CAST")
		return producerClass to ConcurrentCacheData(
			ReentrantLock(),
			producer as StatDataProducer,
			data
		)
	}

	private fun buildAltitudeMap(altitudeData: List<Double2>): StatDataMap {
		return mapOf(
			statEntry(OptimizedAltitudeProducer::class, OptimizedAltitudeProducer(), altitudeData)
		)
	}

	private fun buildLocationMap(locations: List<Location>): StatDataMap {
		return mapOf(
			statEntry(LocationDataProducer::class, LocationDataProducer(), locations)
		)
	}

	private fun location(
		time: Long = 0L,
		lat: Double = 50.0,
		lon: Double = 14.0,
		alt: Double? = null,
		speed: Float? = null
	): Location = Location(
		time = time,
		latitude = lat,
		longitude = lon,
		altitude = alt,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = speed,
		speedAccuracy = null
	)

	// ========================================================================
	// MutableMultiTypeMap
	// ========================================================================

	@Nested
	@DisplayName("MutableMultiTypeMap")
	inner class MutableMultiTypeMapTest {

		@Test
		fun `put and get returns stored value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["count"] = 42
			map["name"] = "test"

			map["count"] shouldBe 42
			map["name"] shouldBe "test"
			map.size shouldBe 2
		}

		@Test
		fun `get throws on missing key`() {
			val map = MutableMultiTypeMap<String, Any>()
			shouldThrow<IllegalArgumentException> {
				map["missing"]
			}
		}

		@Test
		fun `requiredTyped returns correctly typed value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["items"] = listOf(1, 2, 3)

			val result = map.requiredTyped<List<Int>>("items")
			result shouldBe listOf(1, 2, 3)
		}

		@Test
		fun `requiredTyped throws on wrong type`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["value"] = "not a number"

			shouldThrow<IllegalArgumentException> {
				map.requiredTyped<Int>("value")
			}
		}

		@Test
		fun `clear empties the map`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["a"] = 1
			map["b"] = 2
			map.clear()

			map.isEmpty() shouldBe true
			map.size shouldBe 0
		}

		@Test
		fun `remove deletes entry and returns old value`() {
			val map = MutableMultiTypeMap<String, Any>()
			map["key"] = "value"

			val removed = map.remove("key")
			removed shouldBe "value"
			map.containsKey("key") shouldBe false
		}
	}

	// ========================================================================
	// LocationExtractor
	// ========================================================================

	@Nested
	@DisplayName("LocationExtractor")
	inner class LocationExtractorTest {

		private val extractor = LocationExtractor()
		private val multiplier = 1_000_000.0

		@Test
		fun `extracts longitude as X scaled by multiplication constant`() {
			val loc = location(lon = 14.42)
			extractor.getX(loc) shouldBeExactly 14.42 * multiplier
		}

		@Test
		fun `extracts latitude as Y scaled by multiplication constant`() {
			val loc = location(lat = 50.08)
			extractor.getY(loc) shouldBeExactly 50.08 * multiplier
		}

		@Test
		fun `extracts altitude as Z scaled by multiplication constant`() {
			val loc = location(alt = 350.0)
			extractor.getZ(loc) shouldBeExactly 350.0 * multiplier
		}

		@Test
		fun `null altitude returns zero for Z`() {
			val loc = location(alt = null)
			extractor.getZ(loc) shouldBeExactly 0.0
		}
	}

	// ========================================================================
	// AscensionConsumer
	// ========================================================================

	@Nested
	@DisplayName("AscensionConsumer")
	inner class AscensionConsumerTest {

		private val consumer = AscensionConsumer()
		private val context: android.content.Context = mockk()

		@Test
		fun `calculates total ascension from altitude gains`() {
			// Altitude profile: 100 -> 200 -> 150 -> 300
			// Gains: +100, +150 = 250 total ascension
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 100.0),
					Double2(1.0, 200.0),
					Double2(2.0, 150.0),
					Double2(3.0, 300.0)
				)
			)

			consumer.getDistance(context, data) shouldBeExactly 250.0
		}

		@Test
		fun `returns zero for flat altitude profile`() {
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 100.0),
					Double2(1.0, 100.0),
					Double2(2.0, 100.0)
				)
			)

			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for purely descending profile`() {
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 300.0),
					Double2(1.0, 200.0),
					Double2(2.0, 100.0)
				)
			)

			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `single point returns zero`() {
			val data = buildAltitudeMap(listOf(Double2(0.0, 500.0)))
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `empty list returns zero`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}
	}

	// ========================================================================
	// DescensionConsumer
	// ========================================================================

	@Nested
	@DisplayName("DescensionConsumer")
	inner class DescensionConsumerTest {

		private val consumer = DescensionConsumer()
		private val context: android.content.Context = mockk()

		@Test
		fun `calculates total descension from altitude drops`() {
			// Altitude profile: 300 -> 200 -> 250 -> 100
			// Drops: -100, -150 = 250 total descension
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 300.0),
					Double2(1.0, 200.0),
					Double2(2.0, 250.0),
					Double2(3.0, 100.0)
				)
			)

			consumer.getDistance(context, data) shouldBeExactly 250.0
		}

		@Test
		fun `returns zero for purely ascending profile`() {
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 100.0),
					Double2(1.0, 200.0),
					Double2(2.0, 300.0)
				)
			)

			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `empty list returns zero`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}
	}

	// ========================================================================
	// MaxAltitudeConsumer / MinAltitudeConsumer
	// ========================================================================

	@Nested
	@DisplayName("Altitude Extremes")
	inner class AltitudeExtremesTest {

		private val maxConsumer = MaxAltitudeConsumer()
		private val minConsumer = MinAltitudeConsumer()
		private val context: android.content.Context = mockk()

		@Test
		fun `max altitude finds highest point`() {
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 100.0),
					Double2(1.0, 500.0),
					Double2(2.0, 250.0)
				)
			)

			maxConsumer.getDistance(context, data) shouldBeExactly 500.0
		}

		@Test
		fun `min altitude finds lowest point`() {
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, 100.0),
					Double2(1.0, 500.0),
					Double2(2.0, 250.0)
				)
			)

			minConsumer.getDistance(context, data) shouldBeExactly 100.0
		}

		@Test
		fun `max altitude returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			maxConsumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `min altitude returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			minConsumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles negative altitudes correctly`() {
			// Dead Sea is ~-430m below sea level
			val data = buildAltitudeMap(
				listOf(
					Double2(0.0, -430.0),
					Double2(1.0, -200.0),
					Double2(2.0, 50.0)
				)
			)

			maxConsumer.getDistance(context, data) shouldBeExactly 50.0
			minConsumer.getDistance(context, data) shouldBeExactly -430.0
		}
	}

	// ========================================================================
	// MaxSpeedConsumer / AvgSpeedConsumer
	// ========================================================================

	@Nested
	@DisplayName("Speed Consumers")
	inner class SpeedConsumerTest {

		private val maxConsumer = MaxSpeedConsumer()
		private val avgConsumer = AvgSpeedConsumer()
		private val context: android.content.Context = mockk()

		@Test
		fun `max speed finds highest speed from locations`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 5.0f),
					location(time = 1, speed = 15.0f),
					location(time = 2, speed = 10.0f)
				)
			)

			maxConsumer.getSpeed(context, data) shouldBeExactly 15.0
		}

		@Test
		fun `max speed returns zero for empty list`() {
			val data = buildLocationMap(emptyList())
			maxConsumer.getSpeed(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `max speed treats null speed as zero`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = null),
					location(time = 1, speed = 3.0f)
				)
			)

			maxConsumer.getSpeed(context, data) shouldBeExactly 3.0
		}

		@Test
		fun `avg speed calculates mean of non-null speeds`() {
			// Average of 10.0, 20.0, 30.0 = 20.0
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 10.0f),
					location(time = 1, speed = 20.0f),
					location(time = 2, speed = 30.0f)
				)
			)

			avgConsumer.getSpeed(context, data) shouldBeExactly 20.0
		}

		@Test
		fun `avg speed skips null speeds`() {
			// Only non-null speeds: 10.0 and 30.0, average = 20.0
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 10.0f),
					location(time = 1, speed = null),
					location(time = 2, speed = 30.0f)
				)
			)

			avgConsumer.getSpeed(context, data) shouldBeExactly 20.0
		}

		@Test
		fun `avg speed returns NaN for all null speeds`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = null),
					location(time = 1, speed = null)
				)
			)

			// Kotlin average() of empty sequence returns NaN
			avgConsumer.getSpeed(context, data).shouldBeNaN()
		}
	}
}
