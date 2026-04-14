package com.adsamcik.tracker.shared.base.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NumberExtensionsTest {

	@Nested
	@DisplayName("toPercent")
	inner class ToPercent {
		@Test
		fun `1 is 100 percent`() {
			1.0.toPercent() shouldBe 100.0
		}

		@Test
		fun `0 is 0 percent`() {
			0.0.toPercent() shouldBe 0.0
		}

		@Test
		fun `0 point 5 is 50 percent`() {
			0.5.toPercent() shouldBe 50.0
		}

		@Test
		fun `0 point 25 is 25 percent`() {
			0.25.toPercent() shouldBe 25.0
		}

		@Test
		fun `negative value`() {
			(-0.25).toPercent() shouldBe -25.0
		}

		@Test
		fun `above 1 gives more than 100`() {
			1.5.toPercent() shouldBe 150.0
		}
	}

	@Nested
	@DisplayName("toIntPercent")
	inner class ToIntPercent {
		@Test
		fun `1 is 100`() {
			1.0.toIntPercent() shouldBe 100
		}

		@Test
		fun `0 is 0`() {
			0.0.toIntPercent() shouldBe 0
		}

		@Test
		fun `0 point 5 is 50`() {
			0.5.toIntPercent() shouldBe 50
		}

		@Test
		fun `truncates decimal part toward zero`() {
			0.999.toIntPercent() shouldBe 99
		}

		@Test
		fun `negative value truncates toward zero`() {
			(-0.5).toIntPercent() shouldBe -50
		}

		@Test
		fun `small fraction truncates to zero`() {
			0.001.toIntPercent() shouldBe 0
		}
	}
}
