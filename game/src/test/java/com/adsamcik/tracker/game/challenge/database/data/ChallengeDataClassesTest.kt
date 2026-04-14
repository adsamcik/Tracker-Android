package com.adsamcik.tracker.game.challenge.database.data

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChallengeEntry")
class ChallengeEntryTest {

	@Test
	fun `id defaults to 0`() {
		val entry = ChallengeEntry(
			type = ChallengeType.Step,
			startTime = 100L,
			endTime = 200L,
			difficulty = ChallengeDifficulty.MEDIUM,
		)
		entry.id shouldBe 0L
	}

	@Test
	fun `id can be set`() {
		val entry = ChallengeEntry(
			type = ChallengeType.Explorer,
			startTime = 0L,
			endTime = 1000L,
			difficulty = ChallengeDifficulty.HARD,
		)
		entry.id = 42L
		entry.id shouldBe 42L
	}

	@Test
	fun `all fields are stored correctly`() {
		val entry = ChallengeEntry(
			type = ChallengeType.WalkDistance,
			startTime = 500L,
			endTime = 1500L,
			difficulty = ChallengeDifficulty.EASY,
		)
		entry.type shouldBe ChallengeType.WalkDistance
		entry.startTime shouldBe 500L
		entry.endTime shouldBe 1500L
		entry.difficulty shouldBe ChallengeDifficulty.EASY
	}
}

@DisplayName("ChallengeSessionData")
class ChallengeSessionDataTest {

	@Test
	fun `default id is 0`() {
		val data = ChallengeSessionData(isChallengeProcessed = false)
		data.id shouldBe 0
	}

	@Test
	fun `isChallengeProcessed can be toggled`() {
		val data = ChallengeSessionData(id = 1, isChallengeProcessed = false)
		data.isChallengeProcessed shouldBe false
		data.isChallengeProcessed = true
		data.isChallengeProcessed shouldBe true
	}
}
