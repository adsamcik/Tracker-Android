package com.adsamcik.tracker.game.challenge.database.typeconverter

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.shared.base.database.converter.ChallengeDifficultyStringTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.ChallengeTypeConverter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ChallengeTypeConverter (string-based)")
class ChallengeTypeConverterTest {

	private val converter = ChallengeTypeConverter()

	@Nested
	@DisplayName("ChallengeType round-trip")
	inner class TypeRoundTrip {
		@Test
		fun `all types survive string round-trip`() {
			ChallengeType.entries.forEach { type ->
				val stored = converter.fromChallengeType(type)
				val restored = converter.toChallengeType(stored)
				restored shouldBe type
			}
		}

		@Test
		fun `Explorer stores as name string`() {
			converter.fromChallengeType(ChallengeType.Explorer) shouldBe "Explorer"
		}

		@Test
		fun `Speed stores as name string`() {
			converter.fromChallengeType(ChallengeType.Speed) shouldBe "Speed"
		}

		@Test
		fun `Consistency stores as name string`() {
			converter.fromChallengeType(ChallengeType.Consistency) shouldBe "Consistency"
		}

		@Test
		fun `invalid name throws`() {
			assertThrows<IllegalArgumentException> {
				converter.toChallengeType("InvalidType")
			}
		}
	}
}

@DisplayName("ChallengeDifficultyStringTypeConverter")
class ChallengeDifficultyStringTypeConverterTest {

	private val converter = ChallengeDifficultyStringTypeConverter()

	@Nested
	@DisplayName("ChallengeDifficulty round-trip")
	inner class DifficultyRoundTrip {
		@Test
		fun `all difficulties survive string round-trip`() {
			ChallengeDifficulty.entries.forEach { difficulty ->
				val stored = converter.fromDifficulty(difficulty)
				val restored = converter.toDifficulty(stored)
				restored shouldBe difficulty
			}
		}

		@Test
		fun `VERY_EASY stores as name string`() {
			converter.fromDifficulty(ChallengeDifficulty.VERY_EASY) shouldBe "VERY_EASY"
		}

		@Test
		fun `VERY_HARD stores as name string`() {
			converter.fromDifficulty(ChallengeDifficulty.VERY_HARD) shouldBe "VERY_HARD"
		}

		@Test
		fun `invalid name throws`() {
			assertThrows<IllegalArgumentException> {
				converter.toDifficulty("IMPOSSIBLE")
			}
		}
	}
}
