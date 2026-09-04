package com.adsamcik.tracker.points.data

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PointsScoringPolicy")
class PointsScoringPolicyTest {

	@Nested
	inner class DefaultValues {
		@Test
		fun `pointsPerMeterMps defaults to 0_01`() {
			PointsScoringPolicy().pointsPerMeterMps shouldBeExactly 0.01
		}

		@Test
		fun `slopeMultiplier defaults to 12`() {
			PointsScoringPolicy().slopeMultiplier shouldBeExactly 12.0
		}

		@Test
		fun `halfSlope defaults to PI div 4`() {
			PointsScoringPolicy().halfSlope shouldBeExactly kotlin.math.PI / 4
		}

		@Test
		fun `altitudeThreshold defaults to 10`() {
			PointsScoringPolicy().altitudeThreshold shouldBeExactly 10.0
		}

		@Test
		fun `fallbackPointsPerMeter defaults to 0_005`() {
			PointsScoringPolicy().fallbackPointsPerMeter shouldBeExactly 0.005
		}

		@Test
		fun `fallbackPointsPerMinute defaults to 0_5`() {
			PointsScoringPolicy().fallbackPointsPerMinute shouldBeExactly 0.5
		}
	}

	@Nested
	inner class CustomValues {
		@Test
		fun `custom pointsPerMeterMps is stored`() {
			val policy = PointsScoringPolicy(pointsPerMeterMps = 0.05)
			policy.pointsPerMeterMps shouldBeExactly 0.05
		}

		@Test
		fun `custom slopeMultiplier is stored`() {
			val policy = PointsScoringPolicy(slopeMultiplier = 24.0)
			policy.slopeMultiplier shouldBeExactly 24.0
		}

		@Test
		fun `custom halfSlope is stored`() {
			val policy = PointsScoringPolicy(halfSlope = 1.0)
			policy.halfSlope shouldBeExactly 1.0
		}

		@Test
		fun `custom altitudeThreshold is stored`() {
			val policy = PointsScoringPolicy(altitudeThreshold = 20.0)
			policy.altitudeThreshold shouldBeExactly 20.0
		}

		@Test
		fun `custom fallbackPointsPerMeter is stored`() {
			val policy = PointsScoringPolicy(fallbackPointsPerMeter = 0.02)
			policy.fallbackPointsPerMeter shouldBeExactly 0.02
		}

		@Test
		fun `custom fallbackPointsPerMinute is stored`() {
			val policy = PointsScoringPolicy(fallbackPointsPerMinute = 1.5)
			policy.fallbackPointsPerMinute shouldBeExactly 1.5
		}

		@Test
		fun `all custom values together`() {
			val policy = PointsScoringPolicy(
				pointsPerMeterMps = 0.02,
				slopeMultiplier = 6.0,
				halfSlope = 0.5,
				altitudeThreshold = 5.0,
				fallbackPointsPerMeter = 0.01,
				fallbackPointsPerMinute = 1.0,
			)
			policy.pointsPerMeterMps shouldBeExactly 0.02
			policy.slopeMultiplier shouldBeExactly 6.0
			policy.halfSlope shouldBeExactly 0.5
			policy.altitudeThreshold shouldBeExactly 5.0
			policy.fallbackPointsPerMeter shouldBeExactly 0.01
			policy.fallbackPointsPerMinute shouldBeExactly 1.0
		}
	}

	@Nested
	inner class CopyBehavior {
		@Test
		fun `copy preserves all fields`() {
			val original = PointsScoringPolicy()
			val copy = original.copy()
			copy shouldBe original
		}

		@Test
		fun `copy with modified pointsPerMeterMps updates only that field`() {
			val original = PointsScoringPolicy()
			val modified = original.copy(pointsPerMeterMps = 0.99)
			modified.pointsPerMeterMps shouldBeExactly 0.99
			modified.slopeMultiplier shouldBeExactly original.slopeMultiplier
			modified.halfSlope shouldBeExactly original.halfSlope
			modified.altitudeThreshold shouldBeExactly original.altitudeThreshold
			modified.fallbackPointsPerMeter shouldBeExactly original.fallbackPointsPerMeter
			modified.fallbackPointsPerMinute shouldBeExactly original.fallbackPointsPerMinute
		}

		@Test
		fun `copy with modified fallbackPointsPerMinute updates only that field`() {
			val original = PointsScoringPolicy()
			val modified = original.copy(fallbackPointsPerMinute = 3.0)
			modified.fallbackPointsPerMinute shouldBeExactly 3.0
			modified.pointsPerMeterMps shouldBeExactly original.pointsPerMeterMps
		}
	}

	@Nested
	inner class Equality {
		@Test
		fun `two default policies are equal`() {
			PointsScoringPolicy() shouldBe PointsScoringPolicy()
		}

		@Test
		fun `policies with different values are not equal`() {
			PointsScoringPolicy() shouldNotBe PointsScoringPolicy(pointsPerMeterMps = 99.0)
		}

		@Test
		fun `equal policies have same hashCode`() {
			PointsScoringPolicy().hashCode() shouldBe PointsScoringPolicy().hashCode()
		}

		@Test
		fun `different policies have different hashCode`() {
			PointsScoringPolicy().hashCode() shouldNotBe
					PointsScoringPolicy(slopeMultiplier = 999.0).hashCode()
		}
	}

	@Nested
	inner class BoundaryValues {
		@Test
		fun `zero values are accepted`() {
			val policy = PointsScoringPolicy(
				pointsPerMeterMps = 0.0,
				slopeMultiplier = 0.0,
				halfSlope = 0.0,
				altitudeThreshold = 0.0,
				fallbackPointsPerMeter = 0.0,
				fallbackPointsPerMinute = 0.0,
			)
			policy.pointsPerMeterMps shouldBeExactly 0.0
			policy.slopeMultiplier shouldBeExactly 0.0
		}

		@Test
		fun `negative values are accepted`() {
			val policy = PointsScoringPolicy(pointsPerMeterMps = -1.0)
			policy.pointsPerMeterMps shouldBeExactly -1.0
		}

		@Test
		fun `very large values are accepted`() {
			val policy = PointsScoringPolicy(slopeMultiplier = Double.MAX_VALUE)
			policy.slopeMultiplier shouldBeExactly Double.MAX_VALUE
		}
	}

	@Nested
	inner class ToStringTest {
		@Test
		fun `toString contains class name and all fields`() {
			val policy = PointsScoringPolicy()
			val str = policy.toString()
			str shouldBe "PointsScoringPolicy(" +
					"pointsPerMeterMps=0.01, " +
					"slopeMultiplier=12.0, " +
					"halfSlope=${kotlin.math.PI / 4}, " +
					"altitudeThreshold=10.0, " +
					"fallbackPointsPerMeter=0.005, " +
					"fallbackPointsPerMinute=0.5)"
		}
	}
}
