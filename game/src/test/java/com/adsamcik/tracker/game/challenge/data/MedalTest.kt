package com.adsamcik.tracker.game.challenge.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Medal")
class MedalTest {

	@Nested
	@DisplayName("fromCompletion")
	inner class FromCompletion {

		@Test
		fun `null completedAt returns NONE`() {
			Medal.fromCompletion(null, 0, 1000) shouldBe Medal.NONE
		}

		@Test
		fun `zero duration returns BRONZE`() {
			Medal.fromCompletion(100, 100, 100) shouldBe Medal.BRONZE
		}

		@Test
		fun `negative duration returns BRONZE`() {
			Medal.fromCompletion(100, 200, 100) shouldBe Medal.BRONZE
		}

		@Test
		fun `completed at 30 pct of time returns GOLD`() {
			// 1000ms total, completed at 300ms (30%)
			Medal.fromCompletion(300, 0, 1000) shouldBe Medal.GOLD
		}

		@Test
		fun `completed at exactly 40 pct of time returns GOLD`() {
			Medal.fromCompletion(400, 0, 1000) shouldBe Medal.GOLD
		}

		@Test
		fun `completed at 50 pct of time returns SILVER`() {
			Medal.fromCompletion(500, 0, 1000) shouldBe Medal.SILVER
		}

		@Test
		fun `completed at exactly 70 pct of time returns SILVER`() {
			Medal.fromCompletion(700, 0, 1000) shouldBe Medal.SILVER
		}

		@Test
		fun `completed at 80 pct of time returns BRONZE`() {
			Medal.fromCompletion(800, 0, 1000) shouldBe Medal.BRONZE
		}

		@Test
		fun `completed at 100 pct of time returns BRONZE`() {
			Medal.fromCompletion(1000, 0, 1000) shouldBe Medal.BRONZE
		}

		@Test
		fun `completed at start returns GOLD`() {
			Medal.fromCompletion(0, 0, 1000) shouldBe Medal.GOLD
		}
	}

	@Nested
	@DisplayName("xpMultiplier")
	inner class Multipliers {

		@Test
		fun `GOLD has highest multiplier`() {
			(Medal.GOLD.xpMultiplier > Medal.SILVER.xpMultiplier) shouldBe true
		}

		@Test
		fun `SILVER beats BRONZE`() {
			(Medal.SILVER.xpMultiplier > Medal.BRONZE.xpMultiplier) shouldBe true
		}

		@Test
		fun `NONE has zero multiplier`() {
			Medal.NONE.xpMultiplier shouldBe 0.0
		}
	}
}
