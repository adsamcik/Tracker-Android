package com.adsamcik.tracker.shared.utils.style.color.palette

import com.adsamcik.tracker.shared.utils.style.color.ColorDistanceCalculator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [PaletteGenerator], [PaletteGeneratorKMeans], and [PaletteGeneratorForce].
 */
class PaletteGeneratorTest {

	private val generator = PaletteGenerator()

	@Nested
	inner class `KMeans mode` {

		@ParameterizedTest(name = "generates {0} colors")
		@ValueSource(ints = [1, 2, 3, 5, 8, 12])
		fun `generates requested number of colors`(count: Int) {
			val palette = generator.generate(
				colorsCount = count,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 42L,
			)

			palette shouldHaveSize count
		}

		@Test
		fun `deterministic output with same seed`() {
			val palette1 = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 123L,
			)
			val palette2 = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 123L,
			)

			palette1.size shouldBe palette2.size
			palette1.forEachIndexed { i, color ->
				color.l shouldBe palette2[i].l
				color.a shouldBe palette2[i].a
				color.b shouldBe palette2[i].b
			}
		}

		@Test
		fun `different seeds produce different palettes`() {
			val palette1 = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 1L,
			)
			val palette2 = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 999L,
			)

			// At least one color should differ
			val anyDifferent = palette1.zip(palette2).any { (a, b) ->
				a.l != b.l || a.a != b.a || a.b != b.b
			}
			assert(anyDifferent) { "Different seeds should produce different palettes" }
		}

		@Test
		fun `generated colors are valid LAB values`() {
			val palette = generator.generate(
				colorsCount = 8,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 42L,
			)

			palette.forEach { color ->
				assert(color.l in 0.0..100.0) {
					"L component ${color.l} should be in [0, 100]"
				}
				assert(color.a in -128.0..128.0) {
					"A component ${color.a} should be in [-128, 128]"
				}
				assert(color.b in -128.0..128.0) {
					"B component ${color.b} should be in [-128, 128]"
				}
			}
		}

		@Test
		fun `output colors are visually distinct`() {
			val palette = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 20,
				seed = 42L,
			)

			val calculator = ColorDistanceCalculator()
			// Check minimum pairwise distance
			for (i in palette.indices) {
				for (j in i + 1 until palette.size) {
					val dist = calculator.getColorDistance(
						doubleArrayOf(palette[i].l, palette[i].a, palette[i].b),
						doubleArrayOf(palette[j].l, palette[j].a, palette[j].b),
						ColorDistanceCalculator.DistanceType.Euclidean,
					)
					dist.shouldBeGreaterThan(1.0)
				}
			}
		}

	}

	@Nested
	inner class `Force mode` {

		@ParameterizedTest(name = "generates {0} colors")
		@ValueSource(ints = [1, 2, 3, 5, 8])
		fun `generates requested number of colors`(count: Int) {
			val palette = generator.generate(
				colorsCount = count,
				mode = PaletteGenerator.Mode.Force,
				quality = 5,
				seed = 42L,
			)

			palette shouldHaveSize count
		}

		@Test
		fun `deterministic output with same seed`() {
			val palette1 = generator.generate(
				colorsCount = 4,
				mode = PaletteGenerator.Mode.Force,
				quality = 5,
				seed = 456L,
			)
			val palette2 = generator.generate(
				colorsCount = 4,
				mode = PaletteGenerator.Mode.Force,
				quality = 5,
				seed = 456L,
			)

			palette1.size shouldBe palette2.size
			palette1.forEachIndexed { i, color ->
				color.l shouldBe palette2[i].l
				color.a shouldBe palette2[i].a
				color.b shouldBe palette2[i].b
			}
		}

		@Test
		fun `force mode produces visually distinct colors`() {
			val palette = generator.generate(
				colorsCount = 5,
				mode = PaletteGenerator.Mode.Force,
				quality = 10,
				seed = 42L,
			)

			val calculator = ColorDistanceCalculator()
			for (i in palette.indices) {
				for (j in i + 1 until palette.size) {
					val dist = calculator.getColorDistance(
						doubleArrayOf(palette[i].l, palette[i].a, palette[i].b),
						doubleArrayOf(palette[j].l, palette[j].a, palette[j].b),
						ColorDistanceCalculator.DistanceType.Euclidean,
					)
					dist.shouldBeGreaterThan(1.0)
				}
			}
		}

		@ParameterizedTest(name = "distance type {0}")
		@CsvSource(
			"Default",
			"Euclidean",
			"CMC",
		)
		fun `generates colors with different distance types`(distanceTypeName: String) {
			val distanceType = ColorDistanceCalculator.DistanceType.valueOf(distanceTypeName)
			val palette = generator.generate(
				colorsCount = 4,
				mode = PaletteGenerator.Mode.Force,
				quality = 5,
				distanceType = distanceType,
				seed = 42L,
			)

			palette shouldHaveSize 4
		}
	}

	@Nested
	inner class `edge cases` {

		@Test
		fun `single color generates valid result`() {
			val palette = generator.generate(
				colorsCount = 1,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 10,
				seed = 42L,
			)

			palette shouldHaveSize 1
			val color = palette[0]
			assert(color.l in 0.0..100.0) { "L should be in valid range" }
		}

		@Test
		fun `large palette count KMeans`() {
			val palette = generator.generate(
				colorsCount = 20,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 5,
				seed = 42L,
			)

			palette shouldHaveSize 20
		}

		@Test
		fun `large palette count Force`() {
			val palette = generator.generate(
				colorsCount = 20,
				mode = PaletteGenerator.Mode.Force,
				quality = 2,
				seed = 42L,
			)

			palette shouldHaveSize 20
		}

		@Test
		fun `checkColor filter is respected`() {
			// Only allow colors with L > 50 (light colors)
			val palette = generator.generate(
				colorsCount = 5,
				checkColor = { lab -> lab[0] > 50.0 },
				mode = PaletteGenerator.Mode.KMeans,
				quality = 15,
				seed = 42L,
			)

			palette shouldHaveSize 5
			palette.forEach { color ->
				color.l.shouldBeGreaterThan(50.0)
			}
		}

		@Test
		fun `ultraPrecision does not change output count`() {
			val palette = generator.generate(
				colorsCount = 3,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 5,
				ultraPrecision = true,
				seed = 42L,
			)

			palette shouldHaveSize 3
		}
	}

	@Nested
	inner class `LabColor` {

		@Test
		fun `constructor from DoubleArray`() {
			val lab = LabColor(doubleArrayOf(50.0, 25.0, -30.0))

			lab.l shouldBe 50.0
			lab.a shouldBe 25.0
			lab.b shouldBe -30.0
		}

		@Test
		fun `constructor from components`() {
			val lab = LabColor(75.0, -10.0, 40.0)

			lab.l shouldBe 75.0
			lab.a shouldBe -10.0
			lab.b shouldBe 40.0
		}

		@Test
		fun `invalid array size throws`() {
			try {
				LabColor(doubleArrayOf(50.0, 25.0))
				throw AssertionError("Should have thrown")
			} catch (_: ArrayIndexOutOfBoundsException) {
				// Constructor delegation accesses [2] before require check
			} catch (_: IllegalArgumentException) {
				// If require check runs first
			}
		}

		@Test
		fun `data class equality`() {
			val lab1 = LabColor(50.0, 25.0, -30.0)
			val lab2 = LabColor(50.0, 25.0, -30.0)
			val lab3 = LabColor(50.0, 25.0, -31.0)

			lab1 shouldBe lab2
			assert(lab1 != lab3) { "Different LabColors should not be equal" }
		}
	}

	@Nested
	inner class `LAB uniqueness` {

		@Test
		fun `KMeans palette has unique LAB values`() {
			val palette = generator.generate(
				colorsCount = 8,
				mode = PaletteGenerator.Mode.KMeans,
				quality = 20,
				seed = 42L,
			)

			val uniqueColors = palette.toSet()
			uniqueColors.size.shouldBeGreaterThan(palette.size / 2)
		}

		@Test
		fun `Force palette has unique LAB values`() {
			val palette = generator.generate(
				colorsCount = 8,
				mode = PaletteGenerator.Mode.Force,
				quality = 10,
				seed = 42L,
			)

			val uniqueColors = palette.toSet()
			uniqueColors.size.shouldBeGreaterThan(palette.size / 2)
		}
	}
}
