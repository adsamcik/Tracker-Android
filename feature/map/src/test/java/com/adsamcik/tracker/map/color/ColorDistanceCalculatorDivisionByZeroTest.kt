package com.adsamcik.tracker.map.color

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for division-by-zero guards in [ColorDistanceCalculator.simulate].
 * Requires Robolectric because colorblind simulation uses [androidx.core.graphics.ColorUtils].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorDistanceCalculatorDivisionByZeroTest {

	private val calculator = ColorDistanceCalculator()

	private val labBlack = doubleArrayOf(0.0, 0.0, 0.0)
	private val labWhite = doubleArrayOf(100.0, 0.0, 0.0)
	private val labRed = doubleArrayOf(53.2, 80.1, 67.2)
	private val labGreen = doubleArrayOf(87.7, -86.2, 83.2)
	private val labBlue = doubleArrayOf(32.3, 79.2, -107.9)

	@Test
	fun `black color does not produce NaN for protanope`() {
		val result = calculator.getColorDistance(
			labBlack, labWhite,
			ColorDistanceCalculator.DistanceType.CBProtanope
		)
		result.isNaN() shouldBe false
		result.isInfinite() shouldBe false
	}

	@Test
	fun `black color does not produce NaN for deuteranope`() {
		val result = calculator.getColorDistance(
			labBlack, labWhite,
			ColorDistanceCalculator.DistanceType.CBDeuteranope
		)
		result.isNaN() shouldBe false
		result.isInfinite() shouldBe false
	}

	@Test
	fun `black color does not produce NaN for tritanope`() {
		val result = calculator.getColorDistance(
			labBlack, labWhite,
			ColorDistanceCalculator.DistanceType.CBTritanope
		)
		result.isNaN() shouldBe false
		result.isInfinite() shouldBe false
	}

	@Test
	fun `black vs black does not produce NaN for compromise`() {
		val result = calculator.getColorDistance(
			labBlack, labBlack,
			ColorDistanceCalculator.DistanceType.Compromise
		)
		result.isNaN() shouldBe false
		result.isInfinite() shouldBe false
	}

	@Test
	fun `near-black color does not produce NaN`() {
		val nearBlack = doubleArrayOf(0.01, 0.0, 0.0)
		val result = calculator.getColorDistance(
			nearBlack, labWhite,
			ColorDistanceCalculator.DistanceType.CBProtanope
		)
		result.isNaN() shouldBe false
		result.isInfinite() shouldBe false
	}

	@Test
	fun `all colorblind types return finite for black vs red`() {
		val cbTypes = listOf(
			ColorDistanceCalculator.DistanceType.CBProtanope,
			ColorDistanceCalculator.DistanceType.CBDeuteranope,
			ColorDistanceCalculator.DistanceType.CBTritanope,
			ColorDistanceCalculator.DistanceType.Compromise,
		)
		cbTypes.forEach { type ->
			val result = calculator.getColorDistance(labBlack, labRed, type)
			result.isNaN() shouldBe false
			result.isInfinite() shouldBe false
		}
	}

	@Test
	fun `normal colors still produce correct colorblind distances`() {
		val distance = calculator.getColorDistance(
			labRed, labGreen,
			ColorDistanceCalculator.DistanceType.CBProtanope
		)
		distance.isNaN() shouldBe false
		distance.isInfinite() shouldBe false
		distance shouldBeGreaterThan 0.0
	}

	@Test
	fun `compromise distance is finite and positive for distinct colors`() {
		val distance = calculator.getColorDistance(
			labRed, labBlue,
			ColorDistanceCalculator.DistanceType.Compromise
		)
		distance.isNaN() shouldBe false
		distance.isInfinite() shouldBe false
		distance shouldBeGreaterThan 0.0
	}
}
