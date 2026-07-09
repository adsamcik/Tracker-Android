package com.adsamcik.tracker.shared.preferences.extension

import android.content.res.Resources
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringExtensionsTest {

	private lateinit var resources: Resources

	@Before
	fun setUp() {
		Locale.setDefault(Locale.US)
		resources = RuntimeEnvironment.getApplication().resources
	}

	// region FormatMetric

	private data class MetricCase(val meters: Double, val digits: Int, val expected: String)

	private val belowThresholdMetricCases = listOf(
		MetricCase(0.0, 2, "0 m"),
		MetricCase(1.0, 2, "1 m"),
		MetricCase(500.0, 2, "500 m"),
		MetricCase(999.9, 2, "999.9 m"),
		MetricCase(999.99, 2, "999.99 m"),
	)

	private val aboveThresholdMetricCases = listOf(
		MetricCase(1000.0, 2, "1 km"),
		MetricCase(1500.0, 2, "1.5 km"),
		MetricCase(10000.0, 2, "10 km"),
	)

	@Test
	fun `formatMetric below threshold returns meters`() {
		belowThresholdMetricCases.forEach { (meters, digits, expected) ->
			resources.formatMetric(meters, digits) shouldBe expected
		}
	}

	@Test
	fun `formatMetric at or above threshold returns kilometers`() {
		aboveThresholdMetricCases.forEach { (meters, digits, expected) ->
			resources.formatMetric(meters, digits) shouldBe expected
		}
	}

	@Test
	fun `formatMetric very large value includes thousands separator`() {
		resources.formatMetric(1_000_000.0, 2) shouldBe "1,000 km"
	}

	@Test
	fun `formatMetric zero digits rounds value`() {
		resources.formatMetric(1500.0, 0) shouldBe "2 km"
	}

	@Test
	fun `formatMetric negative value stays below threshold`() {
		resources.formatMetric(-500.0, 2) shouldBe "-500 m"
	}
	// endregion

	// region FormatUscs

	private data class UscsCase(val feet: Double, val digits: Int, val expected: String)

	private val belowThresholdUscsCases = listOf(
		UscsCase(0.0, 2, "0 ft"),
		UscsCase(100.0, 2, "100 ft"),
		UscsCase(2640.0, 2, "2,640 ft"),
	)

	private val aboveThresholdUscsCases = listOf(
		UscsCase(5280.0, 2, "1 mi"),
		UscsCase(10560.0, 2, "2 mi"),
	)

	@Test
	fun `formatUscs below threshold returns feet`() {
		belowThresholdUscsCases.forEach { (feet, digits, expected) ->
			resources.formatUscs(feet, digits) shouldBe expected
		}
	}

	@Test
	fun `formatUscs at or above threshold returns miles`() {
		aboveThresholdUscsCases.forEach { (feet, digits, expected) ->
			resources.formatUscs(feet, digits) shouldBe expected
		}
	}

	@Test
	fun `formatUscs just below boundary returns feet`() {
		val result = resources.formatUscs(5279.9, 2)
		result shouldEndWith "ft"
		result shouldContain "5,279.9"
	}
	// endregion

	// region FormatAncientRome

	private data class AncientRomeCase(val passus: Double, val digits: Int, val expected: String)

	private val belowThresholdAncientRomeCases = listOf(
		AncientRomeCase(0.0, 2, "0 pace"),
		AncientRomeCase(500.0, 2, "500 pace"),
		AncientRomeCase(999.9, 2, "999.9 pace"),
	)

	private val aboveThresholdAncientRomeCases = listOf(
		AncientRomeCase(1000.0, 2, "1 roman mile"),
		AncientRomeCase(1500.0, 2, "1.5 roman mile"),
		AncientRomeCase(2000.0, 2, "2 roman mile"),
	)

	@Test
	fun `formatAncientRome below threshold returns passus`() {
		belowThresholdAncientRomeCases.forEach { (passus, digits, expected) ->
			resources.formatAncientRome(passus, digits) shouldBe expected
		}
	}

	@Test
	fun `formatAncientRome at or above threshold returns millepassus`() {
		aboveThresholdAncientRomeCases.forEach { (passus, digits, expected) ->
			resources.formatAncientRome(passus, digits) shouldBe expected
		}
	}
	// endregion

	// region FormatSailing

	@Test
	fun `formatSailing above cable threshold returns cables`() {
		resources.formatSailing(200.0, 2) shouldBe "2 cable"
	}

	@Test
	fun `formatSailing at cable threshold returns cables`() {
		resources.formatSailing(100.0, 2) shouldBe "1 cable"
	}

	@Test
	fun `formatSailing between 1 and cable threshold returns fathoms`() {
		resources.formatSailing(50.0, 2) shouldBe "50 fathom"
	}

	@Test
	fun `formatSailing at 1 fathom returns fathoms`() {
		resources.formatSailing(1.0, 2) shouldBe "1 fathom"
	}

	@Test
	fun `formatSailing below 1 fathom returns nautical miles`() {
		val result = resources.formatSailing(0.5, 2)
		result shouldEndWith "nmi"
	}

	@Test
	fun `formatSailing zero fathoms returns nautical miles`() {
		resources.formatSailing(0.0, 2) shouldBe "0 nmi"
	}
	// endregion

	// region FormatKnots

	@Test
	fun `formatKnots formats knots with abbreviation`() {
		resources.formatKnots(12.0, 1) shouldBe "12 kn"
	}

	@Test
	fun `formatKnots rounds to requested digits`() {
		resources.formatKnots(6.789, 1) shouldBe "6.8 kn"
	}

	@Test
	fun `formatKnots formats zero knots`() {
		resources.formatKnots(0.0, 1) shouldBe "0 kn"
	}
	// endregion

	// region FormatFlying

	@Test
	fun `formatFlying above flight level threshold returns flight level`() {
		resources.formatFlying(3000.0, 2) shouldBe "FL30"
	}

	@Test
	fun `formatFlying at flight level threshold returns flight level`() {
		resources.formatFlying(1000.0, 2) shouldBe "FL10"
	}

	@Test
	fun `formatFlying high altitude flight level`() {
		resources.formatFlying(35000.0, 2) shouldBe "FL350"
	}

	@Test
	fun `formatFlying between 1 and flight level threshold returns feet`() {
		resources.formatFlying(500.0, 2) shouldBe "500 ft"
	}

	@Test
	fun `formatFlying at 1 foot returns feet`() {
		resources.formatFlying(1.0, 2) shouldBe "1 ft"
	}

	@Test
	fun `formatFlying below 1 foot converts to meters`() {
		resources.formatFlying(0.5, 2) shouldBe "0.15 m"
	}

	@Test
	fun `formatFlying zero feet returns zero meters`() {
		resources.formatFlying(0.0, 2) shouldBe "0 m"
	}
	// endregion
}
