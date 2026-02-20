package com.adsamcik.tracker.shared.utils.style.color

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ColorDistanceCalculator")
class ColorDistanceCalculatorTest {

	private val calculator = ColorDistanceCalculator()

	// Well-known LAB values
	private val labBlack = doubleArrayOf(0.0, 0.0, 0.0)
	private val labWhite = doubleArrayOf(100.0, 0.0, 0.0)
	private val labMiddleGray = doubleArrayOf(50.0, 0.0, 0.0)
	private val labRed = doubleArrayOf(53.2, 80.1, 67.2)
	private val labGreen = doubleArrayOf(87.7, -86.2, 83.2)
	private val labBlue = doubleArrayOf(32.3, 79.2, -107.9)

	@Nested
	@DisplayName("Identical colors")
	inner class IdenticalColors {

		@Test
		fun `euclidean distance between identical colors is zero`() {
			val distance = calculator.getColorDistance(
				labBlack, labBlack,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			distance shouldBe 0.0
		}

		@Test
		fun `cmc distance between identical colors is zero`() {
			val distance = calculator.getColorDistance(
				labWhite, labWhite,
				ColorDistanceCalculator.DistanceType.CMC
			)
			distance shouldBe 0.0
		}

		@Test
		fun `default distance between identical colors is zero`() {
			val distance = calculator.getColorDistance(
				labRed, labRed,
				ColorDistanceCalculator.DistanceType.Default
			)
			distance shouldBe 0.0
		}

		@Test
		fun `cmc distance between identical chromatic colors is zero`() {
			val distance = calculator.getColorDistance(
				labGreen, labGreen,
				ColorDistanceCalculator.DistanceType.CMC
			)
			distance shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("Symmetry")
	inner class Symmetry {

		@Test
		fun `euclidean distance is symmetric`() {
			val d1 = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val d2 = calculator.getColorDistance(
				labBlue, labRed,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			d1 shouldBe d2
		}

		@Test
		fun `euclidean distance is symmetric for black and white`() {
			val d1 = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val d2 = calculator.getColorDistance(
				labWhite, labBlack,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			d1 shouldBe d2
		}

		@Test
		fun `default distance is symmetric`() {
			val d1 = calculator.getColorDistance(
				labGreen, labRed,
				ColorDistanceCalculator.DistanceType.Default
			)
			val d2 = calculator.getColorDistance(
				labRed, labGreen,
				ColorDistanceCalculator.DistanceType.Default
			)
			d1 shouldBe d2
		}
	}

	@Nested
	@DisplayName("Triangle inequality")
	inner class TriangleInequality {

		@Test
		fun `euclidean distance satisfies triangle inequality`() {
			val dAB = calculator.getColorDistance(
				labRed, labGreen,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val dBC = calculator.getColorDistance(
				labGreen, labBlue,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val dAC = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			dAC shouldBeLessThan (dAB + dBC + 0.0001) // epsilon for floating point
		}

		@Test
		fun `triangle inequality with black white and gray`() {
			val dBW = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val dBG = calculator.getColorDistance(
				labBlack, labMiddleGray,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val dGW = calculator.getColorDistance(
				labMiddleGray, labWhite,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			dBW shouldBeLessThan (dBG + dGW + 0.0001)
		}
	}

	@Nested
	@DisplayName("Known distances")
	inner class KnownDistances {

		@Test
		fun `euclidean distance between black and white is 100`() {
			val distance = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			distance shouldBe 100.0
		}

		@Test
		fun `euclidean distance between black and middle gray is 50`() {
			val distance = calculator.getColorDistance(
				labBlack, labMiddleGray,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			distance shouldBe 50.0
		}

		@Test
		fun `different colors have positive euclidean distance`() {
			val distance = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			distance shouldBeGreaterThan 0.0
		}

		@Test
		fun `different colors have positive cmc distance`() {
			val distance = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.CMC
			)
			distance shouldBeGreaterThan 0.0
		}

		@Test
		fun `black-white cmc distance is large`() {
			val distance = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.CMC
			)
			distance shouldBeGreaterThan 50.0
		}
	}

	@Nested
	@DisplayName("Distance type consistency")
	inner class DistanceTypeConsistency {

		@Test
		fun `default type equals euclidean type`() {
			val defaultDist = calculator.getColorDistance(
				labRed, labGreen,
				ColorDistanceCalculator.DistanceType.Default
			)
			val euclideanDist = calculator.getColorDistance(
				labRed, labGreen,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			defaultDist shouldBe euclideanDist
		}

		@Test
		fun `cmc distance differs from euclidean`() {
			val cmcDist = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.CMC
			)
			val euclideanDist = calculator.getColorDistance(
				labRed, labBlue,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			// CMC and Euclidean use different formulas, so they should differ
			(cmcDist == euclideanDist) shouldBe false
		}

		@Test
		fun `all non-negative for euclidean`() {
			val pairs = listOf(
				labBlack to labWhite,
				labRed to labGreen,
				labGreen to labBlue,
				labBlue to labRed,
				labMiddleGray to labWhite
			)
			pairs.forEach { (a, b) ->
				val distance = calculator.getColorDistance(
					a, b, ColorDistanceCalculator.DistanceType.Euclidean
				)
				distance shouldBeGreaterThan -0.0001
			}
		}

		@Test
		fun `all non-negative for cmc`() {
			val pairs = listOf(
				labBlack to labWhite,
				labRed to labGreen,
				labGreen to labBlue,
				labBlue to labRed,
				labMiddleGray to labWhite
			)
			pairs.forEach { (a, b) ->
				val distance = calculator.getColorDistance(
					a, b, ColorDistanceCalculator.DistanceType.CMC
				)
				distance shouldBeGreaterThan -0.0001
			}
		}
	}

	@Nested
	@DisplayName("Perceptual ordering")
	inner class PerceptualOrdering {

		@Test
		fun `closer colors have smaller euclidean distance`() {
			val blackToGray = calculator.getColorDistance(
				labBlack, labMiddleGray,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			val blackToWhite = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.Euclidean
			)
			blackToGray shouldBeLessThan blackToWhite
		}

		@Test
		fun `closer colors have smaller cmc distance`() {
			val blackToGray = calculator.getColorDistance(
				labBlack, labMiddleGray,
				ColorDistanceCalculator.DistanceType.CMC
			)
			val blackToWhite = calculator.getColorDistance(
				labBlack, labWhite,
				ColorDistanceCalculator.DistanceType.CMC
			)
			blackToGray shouldBeLessThan blackToWhite
		}
	}
}
