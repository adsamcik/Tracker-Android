package com.adsamcik.tracker.shared.base.misc

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Probability - random distributions")
class ProbabilityTest {

	private companion object {
		const val SAMPLE_COUNT = 1000
	}

	@Nested
	@DisplayName("Uniform distribution")
	inner class UniformDistribution {
		@Test
		fun `uniform int stays in bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.uniform(0, 10)
				value shouldBeGreaterThanOrEqual 0
				value shouldBeLessThan 10
			}
		}

		@Test
		fun `uniform int with negative range`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.uniform(-5, 5)
				value shouldBeGreaterThanOrEqual -5
				value shouldBeLessThan 5
			}
		}

		@Test
		fun `uniform double stays in bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.uniform(0.0, 1.0)
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThan 1.0
			}
		}

		@Test
		fun `uniform double with custom range`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.uniform(5.0, 10.0)
				value shouldBeGreaterThanOrEqual 5.0
				value shouldBeLessThan 10.0
			}
		}

		@Test
		fun `uniform no-arg returns value in 0 to 1`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.uniform()
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThan 1.0
			}
		}
	}

	@Nested
	@DisplayName("Normal distribution")
	inner class NormalDistribution {
		@Test
		fun `normal returns two values`() {
			val result = Probability.normal()
			result.size shouldBe 2
		}

		@Test
		fun `normal with default mean produces values near 0_5`() {
			var sum = 0.0
			val count = SAMPLE_COUNT
			repeat(count) {
				val values = Probability.normal()
				sum += values[0] + values[1]
			}
			val mean = sum / (count * 2)
			// The mean should be roughly near 0.5 (default)
			mean shouldBeGreaterThanOrEqual 0.2
			mean shouldBeLessThanOrEqual 0.8
		}

		@Test
		fun `normal int returns two values`() {
			val result = Probability.normal(50, 10)
			result.size shouldBe 2
		}

		@Test
		fun `normal int truncates doubles`() {
			// Values should be integers (truncated from double)
			repeat(100) {
				val result = Probability.normal(100, 10)
				result[0] shouldBe result[0].toDouble().toInt()
			}
		}
	}

	@Nested
	@DisplayName("Truncated normal distribution")
	inner class TruncatedNormalDistribution {
		@Test
		fun `truncated normal stays within bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.truncatedNormal(0.5, 0.22, 0.0, 1.0)
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThanOrEqual 1.0
			}
		}

		@Test
		fun `truncated normal with tight bounds stays within bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.truncatedNormal(5.0, 1.0, 4.0, 6.0)
				value shouldBeGreaterThanOrEqual 4.0
				value shouldBeLessThanOrEqual 6.0
			}
		}
	}

	@Nested
	@DisplayName("Exponential distribution")
	inner class ExponentialDistribution {
		@Test
		fun `exponential returns values between 0 and 1`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.exponential()
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThanOrEqual 1.0
			}
		}

		@Test
		fun `exponential with custom lambda stays in bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.exponential(2.0)
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThanOrEqual 1.0
			}
		}

		@Test
		fun `exponential with large lambda still in bounds`() {
			repeat(SAMPLE_COUNT) {
				val value = Probability.exponential(10.0)
				value shouldBeGreaterThanOrEqual 0.0
				value shouldBeLessThanOrEqual 1.0
			}
		}
	}
}
