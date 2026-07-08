package com.adsamcik.tracker.shared.preferences.extension

import android.content.res.Resources
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.Locale

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class StringExtensionsTest {

	private lateinit var resources: Resources

	@BeforeEach
	fun setUp() {
		Locale.setDefault(Locale.US)
		resources = RuntimeEnvironment.getApplication().resources
	}

	@Nested
	inner class FormatMetric {

		@ParameterizedTest(name = "{0}m with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"0.0|2|0 m",
				"1.0|2|1 m",
				"500.0|2|500 m",
				"999.9|2|999.9 m",
				"999.99|2|999.99 m"
			]
		)
		fun `below threshold returns meters`(meters: Double, digits: Int, expected: String) {
			resources.formatMetric(meters, digits) shouldBe expected
		}

		@ParameterizedTest(name = "{0}m with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"1000.0|2|1 km",
				"1500.0|2|1.5 km",
				"10000.0|2|10 km"
			]
		)
		fun `at or above threshold returns kilometers`(meters: Double, digits: Int, expected: String) {
			resources.formatMetric(meters, digits) shouldBe expected
		}

		@Test
		fun `very large value includes thousands separator`() {
			resources.formatMetric(1_000_000.0, 2) shouldBe "1,000 km"
		}

		@Test
		fun `zero digits rounds value`() {
			resources.formatMetric(1500.0, 0) shouldBe "2 km"
		}

		@Test
		fun `negative value stays below threshold`() {
			resources.formatMetric(-500.0, 2) shouldBe "-500 m"
		}
	}

	@Nested
	inner class FormatUscs {

		@ParameterizedTest(name = "{0}ft with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"0.0|2|0 ft",
				"100.0|2|100 ft",
				"2640.0|2|2,640 ft"
			]
		)
		fun `below threshold returns feet`(feet: Double, digits: Int, expected: String) {
			resources.formatUscs(feet, digits) shouldBe expected
		}

		@ParameterizedTest(name = "{0}ft with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"5280.0|2|1 mi",
				"10560.0|2|2 mi"
			]
		)
		fun `at or above threshold returns miles`(feet: Double, digits: Int, expected: String) {
			resources.formatUscs(feet, digits) shouldBe expected
		}

		@Test
		fun `just below boundary returns feet`() {
			val result = resources.formatUscs(5279.9, 2)
			result shouldEndWith "ft"
			result shouldContain "5,279.9"
		}
	}

	@Nested
	inner class FormatAncientRome {

		@ParameterizedTest(name = "{0} passus with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"0.0|2|0 pace",
				"500.0|2|500 pace",
				"999.9|2|999.9 pace"
			]
		)
		fun `below threshold returns passus`(passus: Double, digits: Int, expected: String) {
			resources.formatAncientRome(passus, digits) shouldBe expected
		}

		@ParameterizedTest(name = "{0} passus with {1} digits = \"{2}\"")
		@CsvSource(
			delimiter = '|',
			value = [
				"1000.0|2|1 roman mile",
				"1500.0|2|1.5 roman mile",
				"2000.0|2|2 roman mile"
			]
		)
		fun `at or above threshold returns millepassus`(passus: Double, digits: Int, expected: String) {
			resources.formatAncientRome(passus, digits) shouldBe expected
		}
	}

	@Nested
	inner class FormatSailing {

		@Test
		fun `above cable threshold returns cables`() {
			resources.formatSailing(200.0, 2) shouldBe "2 cable"
		}

		@Test
		fun `at cable threshold returns cables`() {
			resources.formatSailing(100.0, 2) shouldBe "1 cable"
		}

		@Test
		fun `between 1 and cable threshold returns fathoms`() {
			resources.formatSailing(50.0, 2) shouldBe "50 fathom"
		}

		@Test
		fun `at 1 fathom returns fathoms`() {
			resources.formatSailing(1.0, 2) shouldBe "1 fathom"
		}

		@Test
		fun `below 1 fathom returns nautical miles`() {
			val result = resources.formatSailing(0.5, 2)
			result shouldEndWith "nmi"
		}

		@Test
		fun `zero fathoms returns nautical miles`() {
			resources.formatSailing(0.0, 2) shouldBe "0 nmi"
		}
	}

	@Nested
	inner class FormatKnots {

		@Test
		fun `formats knots with abbreviation`() {
			resources.formatKnots(12.0, 1) shouldBe "12 kn"
		}

		@Test
		fun `rounds to requested digits`() {
			resources.formatKnots(6.789, 1) shouldBe "6.8 kn"
		}

		@Test
		fun `formats zero knots`() {
			resources.formatKnots(0.0, 1) shouldBe "0 kn"
		}
	}

	@Nested
	inner class FormatFlying {

		@Test
		fun `above flight level threshold returns flight level`() {
			resources.formatFlying(3000.0, 2) shouldBe "FL30"
		}

		@Test
		fun `at flight level threshold returns flight level`() {
			resources.formatFlying(1000.0, 2) shouldBe "FL10"
		}

		@Test
		fun `high altitude flight level`() {
			resources.formatFlying(35000.0, 2) shouldBe "FL350"
		}

		@Test
		fun `between 1 and flight level threshold returns feet`() {
			resources.formatFlying(500.0, 2) shouldBe "500 ft"
		}

		@Test
		fun `at 1 foot returns feet`() {
			resources.formatFlying(1.0, 2) shouldBe "1 ft"
		}

		@Test
		fun `below 1 foot converts to meters`() {
			resources.formatFlying(0.5, 2) shouldBe "0.15 m"
		}

		@Test
		fun `zero feet returns zero meters`() {
			resources.formatFlying(0.0, 2) shouldBe "0 m"
		}
	}
}
