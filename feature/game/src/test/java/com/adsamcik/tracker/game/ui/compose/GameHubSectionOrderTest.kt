package com.adsamcik.tracker.game.ui.compose

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Hub information architecture: Play first, local "This Week" leaderboard
 * second, compact Goals third, Progress after, and the history/personal-best
 * link last. An active session, when present, takes the very top.
 */
class GameHubSectionOrderTest {

	@Test
	fun playComesFirstThenLeaderboardGoalsProgressHistory() {
		gameHubSectionOrder(hasActiveSession = false) shouldBe listOf(
			GameHubSection.PLAY,
			GameHubSection.LEADERBOARD,
			GameHubSection.GOALS,
			GameHubSection.PROGRESS,
			GameHubSection.HISTORY,
		)
	}

	@Test
	fun activeSessionTakesTheTopSlot() {
		gameHubSectionOrder(hasActiveSession = true) shouldBe listOf(
			GameHubSection.ACTIVE_SESSION,
			GameHubSection.PLAY,
			GameHubSection.LEADERBOARD,
			GameHubSection.GOALS,
			GameHubSection.PROGRESS,
			GameHubSection.HISTORY,
		)
	}

	@Test
	fun playAlwaysPrecedesProgress() {
		val order = gameHubSectionOrder(hasActiveSession = false)
		order.indexOf(GameHubSection.PLAY) shouldBe 0
		(order.indexOf(GameHubSection.PLAY) < order.indexOf(GameHubSection.PROGRESS)) shouldBe true
		(order.indexOf(GameHubSection.LEADERBOARD) < order.indexOf(GameHubSection.GOALS)) shouldBe true
		order.last() shouldBe GameHubSection.HISTORY
	}
}
