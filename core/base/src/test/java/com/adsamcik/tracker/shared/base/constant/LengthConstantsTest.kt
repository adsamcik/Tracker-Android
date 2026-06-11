package com.adsamcik.tracker.shared.base.constant

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

@DisplayName("LengthConstants - unit conversion factors")
class LengthConstantsTest {

	@Nested
	@DisplayName("Metric units")
	inner class MetricUnits {
		@Test
		fun `meters in kilometer is 1000`() {
			LengthConstants.METERS_IN_KILOMETER shouldBe 1000.0
		}
	}

	@Nested
	@DisplayName("Imperial units")
	inner class ImperialUnits {
		@Test
		fun `meters in mile is 1609 point 344`() {
			LengthConstants.METERS_IN_MILE shouldBe 1609.344
		}

		@Test
		fun `feet in mile is 5280`() {
			LengthConstants.FEET_IN_MILE shouldBe 5280.0
		}

		@Test
		fun `feet in meters is approximately 3 point 281`() {
			abs(LengthConstants.FEET_IN_METERS - 3.280839895) shouldBeLessThan 1e-9
		}

		@Test
		fun `meters in foot is 0 point 3048`() {
			LengthConstants.METERS_IN_FOOT shouldBe 0.3048
		}

		@Test
		fun `feet per meter is reciprocal of meters per foot`() {
			abs(LengthConstants.FEET_IN_METERS - 1.0 / LengthConstants.METERS_IN_FOOT) shouldBeLessThan 1e-6
		}
	}

	@Nested
	@DisplayName("Nautical units")
	inner class NauticalUnits {
		@Test
		fun `meters in nautical mile is 1852`() {
			LengthConstants.METERS_IN_NAUTICAL_MILE shouldBe 1852.0
		}

		@Test
		fun `meters in fathom is 1 point 8288`() {
			LengthConstants.METERS_IN_FATHOM shouldBe 1.8288
		}

		@Test
		fun `fathoms in cable is 100`() {
			LengthConstants.FATHOMS_IN_CABLE shouldBe 100.0
		}
	}

	@Nested
	@DisplayName("Aviation units")
	inner class AviationUnits {
		@Test
		fun `feet in flight level is 100`() {
			LengthConstants.FEET_IN_FLIGHT_LEVEL shouldBe 100.0
		}
	}

	@Nested
	@DisplayName("Historical units")
	inner class HistoricalUnits {
		@Test
		fun `meters in passus is 1 point 481`() {
			LengthConstants.METERS_IN_PASSUS shouldBe 1.481
		}

		@Test
		fun `passus in mile passus is 1000`() {
			LengthConstants.PASSUS_IN_MILE_PASSUS shouldBe 1000.0
		}
	}

	@Nested
	@DisplayName("Cross-conversion consistency")
	inner class CrossConversion {
		@Test
		fun `mile in feet times foot in meters approximates mile in meters`() {
			val mileFromFeet = LengthConstants.FEET_IN_MILE * LengthConstants.METERS_IN_FOOT
			abs(mileFromFeet - LengthConstants.METERS_IN_MILE) shouldBeLessThan 0.001
		}
	}
}
