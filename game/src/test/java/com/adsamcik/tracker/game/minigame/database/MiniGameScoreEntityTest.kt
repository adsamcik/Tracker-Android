package com.adsamcik.tracker.game.minigame.database

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MiniGameScoreEntity")
class MiniGameScoreEntityTest {

	@Test
	fun `id auto-generates from 0`() {
		val entity = MiniGameScoreEntity(
			gameId = "outrun",
			score = 150.0,
			xpAwarded = 50,
			playedAt = 1000L,
		)
		entity.id shouldBe 0
	}

	@Test
	fun `all fields are set correctly`() {
		val entity = MiniGameScoreEntity(
			id = 42,
			gameId = "territory",
			score = 25.0,
			xpAwarded = 120,
			playedAt = 99999L,
		)
		entity.id shouldBe 42
		entity.gameId shouldBe "territory"
		entity.score shouldBe 25.0
		entity.xpAwarded shouldBe 120
		entity.playedAt shouldBe 99999L
	}

	@Test
	fun `equality`() {
		val a = MiniGameScoreEntity(gameId = "zenwalk", score = 10.0, xpAwarded = 30, playedAt = 1L)
		val b = MiniGameScoreEntity(gameId = "zenwalk", score = 10.0, xpAwarded = 30, playedAt = 1L)
		a shouldBe b
	}
}
