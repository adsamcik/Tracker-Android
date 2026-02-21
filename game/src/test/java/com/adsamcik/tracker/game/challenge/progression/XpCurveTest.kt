package com.adsamcik.tracker.game.challenge.progression

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("XpCurve")
class XpCurveTest {

	@Nested
	@DisplayName("xpForLevel")
	inner class XpForLevelTests {

		@Test
		fun `level 1 requires 30 XP`() {
			XpCurve.xpForLevel(1) shouldBe 30L
		}

		@Test
		fun `level 2 requires 84 XP`() {
			// floor(30 * 2^1.5) = floor(84.85) = 84
			XpCurve.xpForLevel(2) shouldBe 84L
		}

		@Test
		fun `level 10 requires 948 XP`() {
			// floor(30 * 10^1.5) = floor(948.68) = 948
			XpCurve.xpForLevel(10) shouldBe 948L
		}

		@Test
		fun `throws for level 0`() {
			assertThrows<IllegalArgumentException> {
				XpCurve.xpForLevel(0)
			}
		}

		@Test
		fun `throws for negative level`() {
			assertThrows<IllegalArgumentException> {
				XpCurve.xpForLevel(-5)
			}
		}

		@Test
		fun `monotonically increasing for levels 1 to 20`() {
			for (n in 1..19) {
				XpCurve.xpForLevel(n + 1) shouldBeGreaterThan XpCurve.xpForLevel(n)
			}
		}
	}

	@Nested
	@DisplayName("cumulativeXpForLevel")
	inner class CumulativeXpTests {

		@Test
		fun `cumulative XP for level 1 is 0`() {
			XpCurve.cumulativeXpForLevel(1) shouldBe 0L
		}

		@Test
		fun `cumulative XP for level 2 is 30`() {
			XpCurve.cumulativeXpForLevel(2) shouldBe 30L
		}
	}

	@Nested
	@DisplayName("levelForXp")
	inner class LevelForXpTests {

		@Test
		fun `0 XP is level 1`() {
			XpCurve.levelForXp(0) shouldBe 1
		}

		@Test
		fun `30 XP is level 2`() {
			XpCurve.levelForXp(30) shouldBe 2
		}

		@Test
		fun `29 XP is still level 1`() {
			XpCurve.levelForXp(29) shouldBe 1
		}
	}

	@Nested
	@DisplayName("xpIntoCurrentLevel")
	inner class XpIntoCurrentLevelTests {

		@Test
		fun `30 XP means 0 into level 2`() {
			XpCurve.xpIntoCurrentLevel(30) shouldBe 0L
		}

		@Test
		fun `31 XP means 1 into level 2`() {
			XpCurve.xpIntoCurrentLevel(31) shouldBe 1L
		}
	}

	@Nested
	@DisplayName("computeProfile")
	inner class ComputeProfileTests {

		@Test
		fun `0 XP profile is level 1 with 0 progress`() {
			val profile = XpCurve.computeProfile(0)
			profile.level shouldBe 1
			profile.xpIntoCurrentLevel shouldBe 0L
		}

		@Test
		fun `30 XP profile is level 2 with 0 progress`() {
			val profile = XpCurve.computeProfile(30)
			profile.level shouldBe 2
			profile.xpIntoCurrentLevel shouldBe 0L
		}
	}
}
