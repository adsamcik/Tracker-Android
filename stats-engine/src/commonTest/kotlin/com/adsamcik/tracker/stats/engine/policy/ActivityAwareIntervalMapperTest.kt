package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ActivityAwareIntervalMapperTest {

	@Nested
	inner class TierOverrides {
		@Test
		fun `OFF tier returns MAX_VALUE`() {
			ActivityAwareIntervalMapper.getInterval(PolicyTier.OFF) shouldBe Long.MAX_VALUE
		}

		@Test
		fun `AMBIENT tier returns MAX_VALUE`() {
			ActivityAwareIntervalMapper.getInterval(PolicyTier.AMBIENT) shouldBe Long.MAX_VALUE
		}

		@Test
		fun `PRECISION tier returns fixed 3 seconds`() {
			ActivityAwareIntervalMapper.getInterval(PolicyTier.PRECISION) shouldBe 3_000L
		}

		@Test
		fun `PRECISION tier ignores activity type`() {
			val walking = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.PRECISION, DetectedActivityType.WALKING
			)
			val driving = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.PRECISION, DetectedActivityType.IN_VEHICLE
			)
			walking shouldBe driving
			walking shouldBe 3_000L
		}
	}

	@Nested
	inner class ActiveTierDefaults {
		@Test
		fun `walking default is 25 seconds`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.WALKING
			) shouldBe 25_000L
		}

		@Test
		fun `running default is 10 seconds`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING
			) shouldBe 10_000L
		}

		@Test
		fun `cycling default is 8 seconds`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.ON_BICYCLE
			) shouldBe 8_000L
		}

		@Test
		fun `vehicle default is 5 seconds`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.IN_VEHICLE
			) shouldBe 5_000L
		}

		@Test
		fun `unknown activity default is 15 seconds`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.UNKNOWN
			) shouldBe 15_000L
		}

		@Test
		fun `null speed uses default`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, null
			) shouldBe 10_000L
		}

		@Test
		fun `zero speed uses default`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, 0f
			) shouldBe 10_000L
		}
	}

	@Nested
	inner class SpeedRefinement {
		@Test
		fun `high speed running uses minimum interval`() {
			// 18 km/h = 5 m/s → high end of running range
			val interval = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, 5.0f
			)
			interval shouldBeLessThanOrEqual 8_000L
		}

		@Test
		fun `slow running uses maximum interval`() {
			// 5 km/h ≈ 1.39 m/s → below low end of running range (6 km/h)
			val interval = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, 1.39f
			)
			interval shouldBeGreaterThanOrEqual 15_000L
		}

		@Test
		fun `mid-speed running interpolates`() {
			// 12 km/h = 3.33 m/s → middle of running range
			val interval = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, 3.33f
			)
			interval shouldBeGreaterThan 8_000L
			interval shouldBeLessThan 15_000L
		}

		@Test
		fun `higher speed means shorter interval`() {
			val slow = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.IN_VEHICLE, 8.33f // 30 km/h
			)
			val fast = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.IN_VEHICLE, 27.78f // 100 km/h
			)
			fast shouldBeLessThan slow
		}

		@Test
		fun `vehicle at highway speed uses minimum interval`() {
			// 120 km/h = 33.33 m/s
			val interval = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.IN_VEHICLE, 33.33f
			)
			interval shouldBeLessThanOrEqual 5_000L
		}

		@Test
		fun `walking speed refinement range is narrow`() {
			val slow = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.WALKING, 0.83f // 3 km/h
			)
			val fast = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.WALKING, 1.94f // 7 km/h
			)
			// Walking range is 20-25s, so delta is only 5 seconds
			val delta = slow - fast
			assert(delta in 0..5_000L) {
				"Walking interval delta should be 0-5s, got ${delta}ms"
			}
		}
	}

	@Nested
	inner class EdgeCases {
		@Test
		fun `negative speed uses default`() {
			ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.RUNNING, -5.0f
			) shouldBe 10_000L
		}

		@Test
		fun `ON_FOOT uses same range as WALKING`() {
			val walking = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.WALKING
			)
			val onFoot = ActivityAwareIntervalMapper.getInterval(
				PolicyTier.ACTIVE, DetectedActivityType.ON_FOOT
			)
			walking shouldBe onFoot
		}
	}
}
