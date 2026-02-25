package com.adsamcik.tracker.game.challenge.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreakMilestoneTest {
	@Test
	fun forStreak0ReturnsNull() {
		assertNull(StreakMilestone.forStreak(0))
	}

	@Test
	fun forStreak2ReturnsNull() {
		assertNull(StreakMilestone.forStreak(2))
	}

	@Test
	fun forStreak3ReturnsWarmingUp() {
		assertEquals(StreakMilestone.WARMING_UP, StreakMilestone.forStreak(3))
	}

	@Test
	fun forStreak7ReturnsOnFire() {
		assertEquals(StreakMilestone.ON_FIRE, StreakMilestone.forStreak(7))
	}

	@Test
	fun forStreak14ReturnsUnstoppable() {
		assertEquals(StreakMilestone.UNSTOPPABLE, StreakMilestone.forStreak(14))
	}

	@Test
	fun forStreak30ReturnsLegend() {
		assertEquals(StreakMilestone.LEGEND, StreakMilestone.forStreak(30))
	}

	@Test
	fun forStreak100ReturnsLegend() {
		assertEquals(StreakMilestone.LEGEND, StreakMilestone.forStreak(100))
	}

	@Test
	fun forStreak6ReturnsWarmingUp() {
		assertEquals(StreakMilestone.WARMING_UP, StreakMilestone.forStreak(6))
	}
}
