package com.adsamcik.tracker.statistics.data.source.consumer

import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.statistics.data.source.ConcurrentCacheData
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.LocationDataProducer
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.doubles.shouldBeNaN
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.reflect.KClass

@DisplayName("Speed Consumers")
class SpeedConsumerTest {

	private val context: android.content.Context = mockk()

	private fun location(
		time: Long = 0L,
		lat: Double = 50.0,
		lon: Double = 14.0,
		speed: Float? = null
	): Location = Location(
		time = time,
		latitude = lat,
		longitude = lon,
		altitude = null,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = speed,
		speedAccuracy = null
	)

	private fun buildLocationMap(locations: List<Location>): StatDataMap {
		@Suppress("UNCHECKED_CAST")
		return mapOf(
			LocationDataProducer::class as KClass<out StatDataProducer> to ConcurrentCacheData(
				ReentrantLock(),
				LocationDataProducer() as StatDataProducer,
				locations
			)
		)
	}

	// ========================================================================
	// AvgSpeedConsumer
	// ========================================================================

	@Nested
	@DisplayName("AvgSpeedConsumer")
	inner class AvgSpeedTest {

		private val consumer = AvgSpeedConsumer()

		@Test
		fun `calculates average of all non-null speeds`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 10.0f),
					location(time = 1, speed = 20.0f),
					location(time = 2, speed = 30.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 20.0
		}

		@Test
		fun `skips null speeds in average`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 10.0f),
					location(time = 1, speed = null),
					location(time = 2, speed = 30.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 20.0
		}

		@Test
		fun `returns NaN when all speeds are null`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = null),
					location(time = 1, speed = null)
				)
			)
			consumer.getSpeed(context, data).shouldBeNaN()
		}

		@Test
		fun `returns NaN for empty location list`() {
			val data = buildLocationMap(emptyList())
			consumer.getSpeed(context, data).shouldBeNaN()
		}

		@Test
		fun `single point returns that speed`() {
			val data = buildLocationMap(listOf(location(time = 0, speed = 5.5f)))
			consumer.getSpeed(context, data) shouldBeExactly 5.5f.toDouble()
		}

		@Test
		fun `stationary session returns zero average`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 0.0f),
					location(time = 1, speed = 0.0f),
					location(time = 2, speed = 0.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles mixed zero and non-zero speeds`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 0.0f),
					location(time = 1, speed = 10.0f),
					location(time = 2, speed = 0.0f),
					location(time = 3, speed = 10.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 5.0
		}

		@Test
		fun `depends on LocationDataProducer`() {
			consumer.dependsOn shouldBe listOf(LocationDataProducer::class)
		}
	}

	// ========================================================================
	// MaxSpeedConsumer
	// ========================================================================

	@Nested
	@DisplayName("MaxSpeedConsumer")
	inner class MaxSpeedTest {

		private val consumer = MaxSpeedConsumer()

		@Test
		fun `finds highest speed from locations`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 5.0f),
					location(time = 1, speed = 15.0f),
					location(time = 2, speed = 10.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 15.0
		}

		@Test
		fun `returns zero for empty location list`() {
			val data = buildLocationMap(emptyList())
			consumer.getSpeed(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `treats null speed as zero`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = null),
					location(time = 1, speed = 3.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 3.0
		}

		@Test
		fun `returns zero when all speeds are null`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = null),
					location(time = 1, speed = null)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `single point returns that speed`() {
			val data = buildLocationMap(listOf(location(time = 0, speed = 8.0f)))
			consumer.getSpeed(context, data) shouldBeExactly 8.0
		}

		@Test
		fun `stationary session returns zero`() {
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 0.0f),
					location(time = 1, speed = 0.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles high speed values`() {
			// ~120 km/h ≈ 33.33 m/s
			val data = buildLocationMap(
				listOf(
					location(time = 0, speed = 10.0f),
					location(time = 1, speed = 33.33f),
					location(time = 2, speed = 20.0f)
				)
			)
			consumer.getSpeed(context, data) shouldBeExactly 33.33f.toDouble()
		}

		@Test
		fun `depends on LocationDataProducer`() {
			consumer.dependsOn shouldBe listOf(LocationDataProducer::class)
		}
	}
}
