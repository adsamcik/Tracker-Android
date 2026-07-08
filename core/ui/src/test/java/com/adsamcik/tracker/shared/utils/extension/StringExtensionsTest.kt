package com.adsamcik.tracker.shared.utils.extension

import android.content.res.Resources
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.LengthConstants
import com.adsamcik.tracker.shared.preferences.R
import com.adsamcik.tracker.shared.preferences.extension.formatAncientRome
import com.adsamcik.tracker.shared.preferences.extension.formatFlying
import com.adsamcik.tracker.shared.preferences.extension.formatKnots
import com.adsamcik.tracker.shared.preferences.extension.formatMetric
import com.adsamcik.tracker.shared.preferences.extension.formatSailing
import com.adsamcik.tracker.shared.preferences.extension.formatUscs
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StringExtensions")
class StringExtensionsTest {

	private val resources = mockk<Resources>()

	@BeforeEach
	fun setUp() {
		mockkStatic(
			"com.adsamcik.tracker.shared.preferences.extension.StringExtensionsKt"
		)
	}

	@AfterEach
	fun tearDown() {
		unmockkStatic(
			"com.adsamcik.tracker.shared.preferences.extension.StringExtensionsKt"
		)
	}

	@Nested
	@DisplayName("formatDistance")
	inner class FormatDistance {

		@Test
		fun `delegates to formatMetric for Metric system`() {
			every { resources.formatMetric(100.0, 2) } returns "100 m"

			val result = resources.formatDistance(100.0, 2, LengthSystem.Metric)

			result shouldBe "100 m"
			verify { resources.formatMetric(100.0, 2) }
		}

		@Test
		fun `converts meters to feet for Imperial system`() {
			val meters = 10.0
			val expectedFeet = meters * LengthConstants.FEET_IN_METERS
			every { resources.formatUscs(expectedFeet, 2) } returns "32.81 ft"

			val result = resources.formatDistance(meters, 2, LengthSystem.Imperial)

			result shouldBe "32.81 ft"
			verify { resources.formatUscs(expectedFeet, 2) }
		}

		@Test
		fun `converts meters to passus for AncientRoman system`() {
			val meters = 14.81
			val expectedPassus = meters / LengthConstants.METERS_IN_PASSUS
			every { resources.formatAncientRome(expectedPassus, 1) } returns "10 passus"

			val result = resources.formatDistance(meters, 1, LengthSystem.AncientRoman)

			result shouldBe "10 passus"
			verify { resources.formatAncientRome(expectedPassus, 1) }
		}

		@Test
		fun `converts meters to fathoms for Sailing system`() {
			val meters = 18.288
			val expectedFathoms = meters / LengthConstants.METERS_IN_FATHOM
			every { resources.formatSailing(expectedFathoms, 1) } returns "10 ftm"

			val result = resources.formatDistance(meters, 1, LengthSystem.Sailing)

			result shouldBe "10 ftm"
			verify { resources.formatSailing(expectedFathoms, 1) }
		}

		@Test
		fun `converts meters to feet for Flying system`() {
			val meters = 3.048
			val expectedFeet = meters / LengthConstants.METERS_IN_FOOT
			every { resources.formatFlying(expectedFeet, 0) } returns "10 ft"

			val result = resources.formatDistance(meters, 0, LengthSystem.Flying)

			result shouldBe "10 ft"
			verify { resources.formatFlying(expectedFeet, 0) }
		}

		@Test
		fun `handles zero distance for Metric`() {
			every { resources.formatMetric(0.0, 0) } returns "0 m"

			val result = resources.formatDistance(0.0, 0, LengthSystem.Metric)

			result shouldBe "0 m"
		}

		@Test
		fun `Int overload delegates to Double overload for Metric`() {
			every { resources.formatMetric(500.0, 1) } returns "500 m"

			val result = resources.formatDistance(500, 1, LengthSystem.Metric)

			result shouldBe "500 m"
		}

		@Test
		fun `Float overload delegates to Double overload for Metric`() {
			val meters = 250.5f
			every { resources.formatMetric(meters.toDouble(), 2) } returns "250.5 m"

			val result = resources.formatDistance(meters, 2, LengthSystem.Metric)

			result shouldBe "250.5 m"
		}
	}

	@Nested
	@DisplayName("formatSpeed")
	inner class FormatSpeed {

		@Test
		fun `formats per second speed`() {
			every { resources.formatMetric(10.0, 1) } returns "10 m"
			every { resources.getString(R.string.per_second_abbr, "10 m") } returns "10 m/s"

			val result = resources.formatSpeed(10.0, 1, LengthSystem.Metric, SpeedFormat.Second)

			result shouldBe "10 m/s"
		}

		@Test
		fun `formats per minute speed with correct multiplier`() {
			val metersPerSecond = 1.0
			val distancePerMinute = metersPerSecond * Time.MINUTE_IN_SECONDS
			every { resources.formatMetric(distancePerMinute, 0) } returns "60 m"
			every { resources.getString(R.string.per_minute_abbr, "60 m") } returns "60 m/min"

			val result = resources.formatSpeed(
				metersPerSecond, 0, LengthSystem.Metric, SpeedFormat.Minute
			)

			result shouldBe "60 m/min"
		}

		@Test
		fun `formats per hour speed with correct multiplier`() {
			val metersPerSecond = 1.0
			val distancePerHour = metersPerSecond * Time.HOUR_IN_SECONDS
			every { resources.formatMetric(distancePerHour, 1) } returns "3.6 km"
			every { resources.getString(R.string.per_hour_abbr, "3.6 km") } returns "3.6 km/h"

			val result = resources.formatSpeed(
				metersPerSecond, 1, LengthSystem.Metric, SpeedFormat.Hour
			)

			result shouldBe "3.6 km/h"
		}

		@Test
		fun `Float overload delegates to Double`() {
			val metersPerSecond = 5.0f
			every { resources.formatMetric(metersPerSecond.toDouble(), 1) } returns "5 m"
			every { resources.getString(R.string.per_second_abbr, "5 m") } returns "5 m/s"

			val result = resources.formatSpeed(
				metersPerSecond, 1, LengthSystem.Metric, SpeedFormat.Second
			)

			result shouldBe "5 m/s"
		}

		@Test
		fun `formats zero speed`() {
			every { resources.formatMetric(0.0, 1) } returns "0 m"
			every { resources.getString(R.string.per_second_abbr, "0 m") } returns "0 m/s"

			val result = resources.formatSpeed(0.0, 1, LengthSystem.Metric, SpeedFormat.Second)

			result shouldBe "0 m/s"
		}

		@Test
		fun `formats speed with Imperial system`() {
			val metersPerSecond = 10.0
			val expectedFeet = metersPerSecond * LengthConstants.FEET_IN_METERS
			every { resources.formatUscs(expectedFeet, 1) } returns "32.8 ft"
			every { resources.getString(R.string.per_second_abbr, "32.8 ft") } returns "32.8 ft/s"

			val result = resources.formatSpeed(
				metersPerSecond, 1, LengthSystem.Imperial, SpeedFormat.Second
			)

			result shouldBe "32.8 ft/s"
		}

		@Test
		fun `formats speed in knots for Sailing system regardless of speed format`() {
			val metersPerSecond = 1.0
			val expectedKnots = metersPerSecond * Time.HOUR_IN_SECONDS / LengthConstants.METERS_IN_NAUTICAL_MILE
			every { resources.formatKnots(expectedKnots, 1) } returns "1.9 kn"

			val result = resources.formatSpeed(
				metersPerSecond, 1, LengthSystem.Sailing, SpeedFormat.Second
			)

			result shouldBe "1.9 kn"
			verify { resources.formatKnots(expectedKnots, 1) }
		}
	}
}
