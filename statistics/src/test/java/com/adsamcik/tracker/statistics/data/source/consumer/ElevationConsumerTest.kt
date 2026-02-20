package com.adsamcik.tracker.statistics.data.source.consumer

import com.adsamcik.tracker.shared.base.misc.Double2
import com.adsamcik.tracker.statistics.data.source.ConcurrentCacheData
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.OptimizedAltitudeProducer
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.reflect.KClass

@DisplayName("Elevation Consumers")
class ElevationConsumerTest {

	private val context: android.content.Context = mockk()

	private fun buildAltitudeMap(altitudeData: List<Double2>): StatDataMap {
		@Suppress("UNCHECKED_CAST")
		return mapOf(
			OptimizedAltitudeProducer::class as KClass<out StatDataProducer> to ConcurrentCacheData(
				ReentrantLock(),
				OptimizedAltitudeProducer() as StatDataProducer,
				altitudeData
			)
		)
	}

	/** Helper to create altitude point: x = time offset, y = altitude */
	private fun alt(time: Double, altitude: Double) = Double2(time, altitude)

	// ========================================================================
	// AscensionConsumer
	// ========================================================================

	@Nested
	@DisplayName("AscensionConsumer")
	inner class AscensionTest {

		private val consumer = AscensionConsumer()

		@Test
		fun `calculates total ascension from mixed profile`() {
			// 100 -> 200 (+100), 200 -> 150 (drop), 150 -> 300 (+150) = 250
			val data = buildAltitudeMap(
				listOf(alt(0.0, 100.0), alt(1.0, 200.0), alt(2.0, 150.0), alt(3.0, 300.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 250.0
		}

		@Test
		fun `returns zero for flat terrain`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 100.0), alt(1.0, 100.0), alt(2.0, 100.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for purely descending profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 300.0), alt(1.0, 200.0), alt(2.0, 100.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for single point`() {
			val data = buildAltitudeMap(listOf(alt(0.0, 500.0)))
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles purely ascending profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 0.0), alt(1.0, 100.0), alt(2.0, 200.0), alt(3.0, 300.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 300.0
		}

		@Test
		fun `handles noisy altitude data with small oscillations`() {
			// Simulates GPS noise: 100 -> 102 -> 99 -> 103 -> 98 -> 105
			val data = buildAltitudeMap(
				listOf(
					alt(0.0, 100.0),
					alt(1.0, 102.0),
					alt(2.0, 99.0),
					alt(3.0, 103.0),
					alt(4.0, 98.0),
					alt(5.0, 105.0)
				)
			)
			// Gains: +2, +4, +7 = 13
			consumer.getDistance(context, data) shouldBeExactly 13.0
		}

		@Test
		fun `handles negative altitudes correctly`() {
			// -50 -> -10 (+40), -10 -> -30 (drop), -30 -> 20 (+50) = 90
			val data = buildAltitudeMap(
				listOf(alt(0.0, -50.0), alt(1.0, -10.0), alt(2.0, -30.0), alt(3.0, 20.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 90.0
		}

		@Test
		fun `depends on OptimizedAltitudeProducer`() {
			consumer.dependsOn shouldBe listOf(OptimizedAltitudeProducer::class)
		}
	}

	// ========================================================================
	// DescensionConsumer
	// ========================================================================

	@Nested
	@DisplayName("DescensionConsumer")
	inner class DescensionTest {

		private val consumer = DescensionConsumer()

		@Test
		fun `calculates total descension from mixed profile`() {
			// 300 -> 200 (-100), 200 -> 250 (gain), 250 -> 100 (-150) = 250
			val data = buildAltitudeMap(
				listOf(alt(0.0, 300.0), alt(1.0, 200.0), alt(2.0, 250.0), alt(3.0, 100.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 250.0
		}

		@Test
		fun `returns zero for flat terrain`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 200.0), alt(1.0, 200.0), alt(2.0, 200.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for purely ascending profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 100.0), alt(1.0, 200.0), alt(2.0, 300.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for single point`() {
			val data = buildAltitudeMap(listOf(alt(0.0, 500.0)))
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles purely descending profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 300.0), alt(1.0, 200.0), alt(2.0, 100.0), alt(3.0, 0.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 300.0
		}

		@Test
		fun `handles noisy altitude data with small oscillations`() {
			// 100 -> 102 -> 99 -> 103 -> 98 -> 105
			// Drops: -3, -5 = 8
			val data = buildAltitudeMap(
				listOf(
					alt(0.0, 100.0),
					alt(1.0, 102.0),
					alt(2.0, 99.0),
					alt(3.0, 103.0),
					alt(4.0, 98.0),
					alt(5.0, 105.0)
				)
			)
			consumer.getDistance(context, data) shouldBeExactly 8.0
		}

		@Test
		fun `handles negative altitudes correctly`() {
			// 20 -> -10 (-30), -10 -> -5 (gain), -5 -> -50 (-45) = 75
			val data = buildAltitudeMap(
				listOf(alt(0.0, 20.0), alt(1.0, -10.0), alt(2.0, -5.0), alt(3.0, -50.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 75.0
		}

		@Test
		fun `ascension and descension are complementary`() {
			val ascConsumer = AscensionConsumer()
			val profile = listOf(
				alt(0.0, 100.0), alt(1.0, 200.0), alt(2.0, 150.0),
				alt(3.0, 300.0), alt(4.0, 100.0)
			)
			val data = buildAltitudeMap(profile)

			val ascension = ascConsumer.getDistance(context, data)
			val descension = consumer.getDistance(context, data)

			// Net change = start - end = 100 - 100 = 0, so ascension == descension
			ascension shouldBeExactly descension
		}

		@Test
		fun `depends on OptimizedAltitudeProducer`() {
			consumer.dependsOn shouldBe listOf(OptimizedAltitudeProducer::class)
		}
	}

	// ========================================================================
	// MaxAltitudeConsumer
	// ========================================================================

	@Nested
	@DisplayName("MaxAltitudeConsumer")
	inner class MaxAltitudeTest {

		private val consumer = MaxAltitudeConsumer()

		@Test
		fun `finds highest point in profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 100.0), alt(1.0, 500.0), alt(2.0, 250.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 500.0
		}

		@Test
		fun `returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `single point returns that altitude`() {
			val data = buildAltitudeMap(listOf(alt(0.0, 350.0)))
			consumer.getDistance(context, data) shouldBeExactly 350.0
		}

		@Test
		fun `handles negative altitudes`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, -430.0), alt(1.0, -200.0), alt(2.0, -100.0))
			)
			consumer.getDistance(context, data) shouldBeExactly -100.0
		}

		@Test
		fun `handles mix of negative and positive altitudes`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, -50.0), alt(1.0, 0.0), alt(2.0, 150.0), alt(3.0, -10.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 150.0
		}

		@Test
		fun `all same altitude returns that value`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 200.0), alt(1.0, 200.0), alt(2.0, 200.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 200.0
		}

		@Test
		fun `handles high altitude values`() {
			// Everest base camp ~5364m
			val data = buildAltitudeMap(
				listOf(alt(0.0, 4000.0), alt(1.0, 5364.0), alt(2.0, 4800.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 5364.0
		}

		@Test
		fun `depends on OptimizedAltitudeProducer`() {
			consumer.dependsOn shouldBe listOf(OptimizedAltitudeProducer::class)
		}
	}

	// ========================================================================
	// MinAltitudeConsumer
	// ========================================================================

	@Nested
	@DisplayName("MinAltitudeConsumer")
	inner class MinAltitudeTest {

		private val consumer = MinAltitudeConsumer()

		@Test
		fun `finds lowest point in profile`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 100.0), alt(1.0, 500.0), alt(2.0, 250.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 100.0
		}

		@Test
		fun `returns zero for empty list`() {
			val data = buildAltitudeMap(emptyList())
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `single point returns that altitude`() {
			val data = buildAltitudeMap(listOf(alt(0.0, 350.0)))
			consumer.getDistance(context, data) shouldBeExactly 350.0
		}

		@Test
		fun `handles negative altitudes`() {
			// Dead Sea ~-430m
			val data = buildAltitudeMap(
				listOf(alt(0.0, -430.0), alt(1.0, -200.0), alt(2.0, 50.0))
			)
			consumer.getDistance(context, data) shouldBeExactly -430.0
		}

		@Test
		fun `handles mix of negative and positive altitudes`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 50.0), alt(1.0, -20.0), alt(2.0, 150.0), alt(3.0, 0.0))
			)
			consumer.getDistance(context, data) shouldBeExactly -20.0
		}

		@Test
		fun `all same altitude returns that value`() {
			val data = buildAltitudeMap(
				listOf(alt(0.0, 200.0), alt(1.0, 200.0), alt(2.0, 200.0))
			)
			consumer.getDistance(context, data) shouldBeExactly 200.0
		}

		@Test
		fun `max and min consumers agree on single-point list`() {
			val maxConsumer = MaxAltitudeConsumer()
			val data = buildAltitudeMap(listOf(alt(0.0, 777.0)))

			consumer.getDistance(context, data) shouldBeExactly maxConsumer.getDistance(context, data)
		}

		@Test
		fun `min is always less than or equal to max`() {
			val maxConsumer = MaxAltitudeConsumer()
			val data = buildAltitudeMap(
				listOf(
					alt(0.0, 100.0), alt(1.0, 500.0), alt(2.0, 50.0),
					alt(3.0, 300.0), alt(4.0, 200.0)
				)
			)

			val max = maxConsumer.getDistance(context, data)
			val min = consumer.getDistance(context, data)
			(max - min) shouldBeGreaterThan 0.0
		}

		@Test
		fun `depends on OptimizedAltitudeProducer`() {
			consumer.dependsOn shouldBe listOf(OptimizedAltitudeProducer::class)
		}
	}
}
