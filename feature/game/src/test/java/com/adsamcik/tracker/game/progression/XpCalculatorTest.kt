package com.adsamcik.tracker.game.progression

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("XpCalculator")
class XpCalculatorTest {

	@Nested
	@DisplayName("sessionXp")
	inner class SessionXpTests {

		@Test
		fun `combines distance and duration`() {
			// 1000m*0.05 + 10min*0.2 = 50 + 2 = 52
			XpCalculator.sessionXp(
				distanceM = 1000f,
				durationMs = 10 * 60_000L,
			) shouldBe 52
		}

		@Test
		fun `distance and duration remain independently rewardable`() {
			XpCalculator.sessionXp(distanceM = 1000f, durationMs = 0L) shouldBe 50
			XpCalculator.sessionXp(distanceM = 0f, durationMs = 10 * 60_000L) shouldBe 2
		}

		@Test
		fun `duration is capped at 120 minutes`() {
			val twoHours = XpCalculator.sessionXp(0f, 120 * 60_000L)
			val tenHours = XpCalculator.sessionXp(0f, 600 * 60_000L)
			twoHours shouldBe tenHours
		}

		@Test
		fun `is capped at SESSION_CAP`() {
			XpCalculator.sessionXp(
				distanceM = 1_000_000f,
				durationMs = 120 * 60_000L,
			) shouldBe XpCalculator.SESSION_CAP
		}

		@Test
		fun `empty session yields zero`() {
			XpCalculator.sessionXp(0f, 0L) shouldBe 0
		}
	}

	@Nested
	@DisplayName("miniGameXp")
	inner class MiniGameXpTests {

		@Test
		fun `credits points one to one`() {
			XpCalculator.miniGameXp(42) shouldBe 42
		}

		@Test
		fun `negative points clamp to zero`() {
			XpCalculator.miniGameXp(-5) shouldBe 0
		}
	}
}
