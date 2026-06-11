package com.adsamcik.tracker.stats.api.threshold

import com.adsamcik.tracker.stats.api.value.SpeedMps
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SpeedThresholdsTest {

	@Nested
	inner class ThresholdValues {

		@Test
		fun `MAX_WALK is 2_5 mps`() {
			SpeedThresholds.MAX_WALK shouldBe SpeedMps(2.5f)
		}

		@Test
		fun `MAX_RUN is 6_0 mps`() {
			SpeedThresholds.MAX_RUN shouldBe SpeedMps(6.0f)
		}

		@Test
		fun `MIN_CYCLE is 3_0 mps`() {
			SpeedThresholds.MIN_CYCLE shouldBe SpeedMps(3.0f)
		}

		@Test
		fun `MAX_CYCLE is 12_0 mps`() {
			SpeedThresholds.MAX_CYCLE shouldBe SpeedMps(12.0f)
		}

		@Test
		fun `MIN_DEFINITE_VEHICLE is 15_0 mps`() {
			SpeedThresholds.MIN_DEFINITE_VEHICLE shouldBe SpeedMps(15.0f)
		}

		@Test
		fun `MAX_ON_FOOT is 4_5 mps`() {
			SpeedThresholds.MAX_ON_FOOT shouldBe SpeedMps(4.5f)
		}

		@Test
		fun `STILLNESS is 0_3 mps`() {
			SpeedThresholds.STILLNESS shouldBe SpeedMps(0.3f)
		}

		@Test
		fun `MIN_HIGH_SPEED_RAIL is 50_0 mps`() {
			SpeedThresholds.MIN_HIGH_SPEED_RAIL shouldBe SpeedMps(50.0f)
		}
	}

	@Nested
	inner class ThresholdOrdering {

		@Test
		fun `STILLNESS is less than MAX_WALK`() {
			assert(SpeedThresholds.STILLNESS < SpeedThresholds.MAX_WALK) {
				"STILLNESS should be less than MAX_WALK"
			}
		}

		@Test
		fun `MAX_WALK is less than MAX_ON_FOOT`() {
			assert(SpeedThresholds.MAX_WALK < SpeedThresholds.MAX_ON_FOOT) {
				"MAX_WALK should be less than MAX_ON_FOOT"
			}
		}

		@Test
		fun `MAX_ON_FOOT is less than MAX_RUN`() {
			assert(SpeedThresholds.MAX_ON_FOOT < SpeedThresholds.MAX_RUN) {
				"MAX_ON_FOOT should be less than MAX_RUN"
			}
		}

		@Test
		fun `MIN_CYCLE is less than MAX_CYCLE`() {
			assert(SpeedThresholds.MIN_CYCLE < SpeedThresholds.MAX_CYCLE) {
				"MIN_CYCLE should be less than MAX_CYCLE"
			}
		}

		@Test
		fun `MAX_CYCLE is less than MIN_DEFINITE_VEHICLE`() {
			assert(SpeedThresholds.MAX_CYCLE < SpeedThresholds.MIN_DEFINITE_VEHICLE) {
				"MAX_CYCLE should be less than MIN_DEFINITE_VEHICLE"
			}
		}

		@Test
		fun `MIN_DEFINITE_VEHICLE is less than MIN_HIGH_SPEED_RAIL`() {
			assert(SpeedThresholds.MIN_DEFINITE_VEHICLE < SpeedThresholds.MIN_HIGH_SPEED_RAIL) {
				"MIN_DEFINITE_VEHICLE should be less than MIN_HIGH_SPEED_RAIL"
			}
		}

		@Test
		fun `walking range overlaps with cycling range`() {
			// MAX_WALK (2.5) < MIN_CYCLE (3.0) is expected — no overlap
			assert(SpeedThresholds.MAX_WALK < SpeedThresholds.MIN_CYCLE) {
				"MAX_WALK should be less than MIN_CYCLE for clean classification"
			}
		}
	}

	@Nested
	inner class AllThresholdsPositive {

		@Test
		fun `all thresholds are positive`() {
			val thresholds = listOf(
				SpeedThresholds.MAX_WALK,
				SpeedThresholds.MAX_RUN,
				SpeedThresholds.MIN_CYCLE,
				SpeedThresholds.MAX_CYCLE,
				SpeedThresholds.MIN_DEFINITE_VEHICLE,
				SpeedThresholds.MAX_ON_FOOT,
				SpeedThresholds.STILLNESS,
				SpeedThresholds.MIN_HIGH_SPEED_RAIL,
			)
			thresholds.forEach { threshold ->
				assert(threshold > SpeedMps.ZERO) {
					"Threshold $threshold should be positive"
				}
			}
		}
	}
}
