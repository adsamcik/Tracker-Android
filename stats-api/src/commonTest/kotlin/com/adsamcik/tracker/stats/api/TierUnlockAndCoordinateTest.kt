package com.adsamcik.tracker.stats.api

import com.adsamcik.tracker.stats.api.value.CoordinateE7
import com.adsamcik.tracker.stats.api.value.LatE7
import com.adsamcik.tracker.stats.api.value.LonE7
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TierUnlockAndCoordinateTest {

	@Nested
	inner class TierUnlockTests {

		@Test
		fun `TierUnlock preserves achievement id and tier`() {
			val unlock = TierUnlock(achievementId = "explorer_cells", tier = AchievementTier.GOLD)
			unlock.achievementId shouldBe "explorer_cells"
			unlock.tier shouldBe AchievementTier.GOLD
		}

		@Test
		fun `TierUnlock data class equality`() {
			val a = TierUnlock("test", AchievementTier.BRONZE)
			val b = TierUnlock("test", AchievementTier.BRONZE)
			a shouldBe b
		}

		@Test
		fun `TierUnlock with different tier is not equal`() {
			val a = TierUnlock("test", AchievementTier.BRONZE)
			val b = TierUnlock("test", AchievementTier.SILVER)
			assert(a != b)
		}

		@Test
		fun `TierUnlock with different achievement id is not equal`() {
			val a = TierUnlock("test_a", AchievementTier.BRONZE)
			val b = TierUnlock("test_b", AchievementTier.BRONZE)
			assert(a != b)
		}

		@Test
		fun `TierUnlock copy preserves unmodified fields`() {
			val original = TierUnlock("explorer_cells", AchievementTier.BRONZE)
			val copied = original.copy(tier = AchievementTier.DIAMOND)
			copied.achievementId shouldBe "explorer_cells"
			copied.tier shouldBe AchievementTier.DIAMOND
		}
	}

	@Nested
	inner class CoordinateE7Tests {

		@Test
		fun `CoordinateE7 preserves lat and lon`() {
			val coord = CoordinateE7(
				lat = LatE7(487_000_000),
				lon = LonE7(163_000_000),
			)
			coord.lat shouldBe LatE7(487_000_000)
			coord.lon shouldBe LonE7(163_000_000)
		}

		@Test
		fun `CoordinateE7 data class equality`() {
			val a = CoordinateE7(LatE7(100_000_000), LonE7(200_000_000))
			val b = CoordinateE7(LatE7(100_000_000), LonE7(200_000_000))
			a shouldBe b
		}

		@Test
		fun `CoordinateE7 with different lat is not equal`() {
			val a = CoordinateE7(LatE7(100_000_000), LonE7(200_000_000))
			val b = CoordinateE7(LatE7(200_000_000), LonE7(200_000_000))
			assert(a != b)
		}

		@Test
		fun `CoordinateE7 components support conversion to degrees`() {
			val coord = CoordinateE7(
				lat = LatE7.fromDegrees(48.8566),
				lon = LonE7.fromDegrees(2.3522),
			)
			assert(kotlin.math.abs(coord.lat.toDegrees() - 48.8566) < 0.001)
			assert(kotlin.math.abs(coord.lon.toDegrees() - 2.3522) < 0.001)
		}

		@Test
		fun `CoordinateE7 copy preserves unmodified fields`() {
			val original = CoordinateE7(LatE7(100_000_000), LonE7(200_000_000))
			val copied = original.copy(lat = LatE7(300_000_000))
			copied.lat shouldBe LatE7(300_000_000)
			copied.lon shouldBe LonE7(200_000_000)
		}
	}

	@Nested
	inner class PlausibilityResultTests {

		@Test
		fun `Plausible is a singleton`() {
			val a = PlausibilityResult.Plausible
			val b = PlausibilityResult.Plausible
			a shouldBe b
		}

		@Test
		fun `Implausible preserves speed and threshold`() {
			val result = PlausibilityResult.Implausible(
				averageSpeedMps = 50.0f,
				thresholdMps = 4.17f,
			)
			result.averageSpeedMps shouldBe 50.0f
			result.thresholdMps shouldBe 4.17f
		}

		@Test
		fun `Implausible data class equality`() {
			val a = PlausibilityResult.Implausible(10f, 5f)
			val b = PlausibilityResult.Implausible(10f, 5f)
			a shouldBe b
		}
	}
}
