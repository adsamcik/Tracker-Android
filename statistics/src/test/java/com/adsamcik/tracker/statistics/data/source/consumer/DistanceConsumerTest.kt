package com.adsamcik.tracker.statistics.data.source.consumer

import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.data.source.ConcurrentCacheData
import com.adsamcik.tracker.statistics.data.source.StatDataMap
import com.adsamcik.tracker.statistics.data.source.abstraction.StatDataProducer
import com.adsamcik.tracker.statistics.data.source.producer.TrackerSessionProducer
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock
import kotlin.reflect.KClass

@DisplayName("Distance Consumers")
class DistanceConsumerTest {

	private val context: android.content.Context = mockk()

	private fun buildSessionMap(session: TrackerSession): StatDataMap {
		@Suppress("UNCHECKED_CAST")
		return mapOf(
			TrackerSessionProducer::class as KClass<out StatDataProducer> to ConcurrentCacheData(
				ReentrantLock(),
				TrackerSessionProducer() as StatDataProducer,
				session
			)
		)
	}

	// ========================================================================
	// DistanceConsumer (total distance)
	// ========================================================================

	@Nested
	@DisplayName("DistanceConsumer")
	inner class TotalDistanceTest {

		private val consumer = DistanceConsumer()

		@Test
		fun `returns total distance from session`() {
			val data = buildSessionMap(TrackerSession(distanceInM = 1234.5f))
			consumer.getDistance(context, data) shouldBeExactly 1234.5.toFloat().toDouble()
		}

		@Test
		fun `returns zero when session has no distance`() {
			val data = buildSessionMap(TrackerSession(distanceInM = 0f))
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles very short distance`() {
			val data = buildSessionMap(TrackerSession(distanceInM = 0.5f))
			consumer.getDistance(context, data) shouldBeExactly 0.5.toFloat().toDouble()
		}

		@Test
		fun `handles very long distance`() {
			// Marathon distance ~42195 meters
			val data = buildSessionMap(TrackerSession(distanceInM = 42195.0f))
			consumer.getDistance(context, data) shouldBeExactly 42195.0f.toDouble()
		}

		@Test
		fun `depends on TrackerSessionProducer`() {
			consumer.dependsOn shouldBe listOf(TrackerSessionProducer::class)
		}
	}

	// ========================================================================
	// DistanceOnFootConsumer
	// ========================================================================

	@Nested
	@DisplayName("DistanceOnFootConsumer")
	inner class OnFootDistanceTest {

		private val consumer = DistanceOnFootConsumer()

		@Test
		fun `returns on-foot distance from session`() {
			val data = buildSessionMap(
				TrackerSession(distanceInM = 5000f, distanceOnFootInM = 3200f)
			)
			consumer.getDistance(context, data) shouldBeExactly 3200.0f.toDouble()
		}

		@Test
		fun `returns zero when no on-foot distance`() {
			val data = buildSessionMap(
				TrackerSession(distanceInM = 5000f, distanceOnFootInM = 0f)
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `on-foot distance can equal total distance`() {
			val data = buildSessionMap(
				TrackerSession(distanceInM = 1000f, distanceOnFootInM = 1000f)
			)
			consumer.getDistance(context, data) shouldBeExactly 1000.0f.toDouble()
		}

		@Test
		fun `depends on TrackerSessionProducer`() {
			consumer.dependsOn shouldBe listOf(TrackerSessionProducer::class)
		}
	}

	// ========================================================================
	// DistanceInVehicleConsumer
	// ========================================================================

	@Nested
	@DisplayName("DistanceInVehicleConsumer")
	inner class InVehicleDistanceTest {

		private val consumer = DistanceInVehicleConsumer()

		@Test
		fun `returns in-vehicle distance from session`() {
			val data = buildSessionMap(
				TrackerSession(distanceInM = 10000f, distanceInVehicleInM = 8500f)
			)
			consumer.getDistance(context, data) shouldBeExactly 8500.0f.toDouble()
		}

		@Test
		fun `returns zero when no vehicle distance`() {
			val data = buildSessionMap(
				TrackerSession(distanceInM = 5000f, distanceInVehicleInM = 0f)
			)
			consumer.getDistance(context, data) shouldBeExactly 0.0
		}

		@Test
		fun `handles large vehicle distance`() {
			// Long road trip ~500 km
			val data = buildSessionMap(TrackerSession(distanceInVehicleInM = 500_000f))
			consumer.getDistance(context, data) shouldBeExactly 500_000f.toDouble()
		}

		@Test
		fun `depends on TrackerSessionProducer`() {
			consumer.dependsOn shouldBe listOf(TrackerSessionProducer::class)
		}
	}
}
