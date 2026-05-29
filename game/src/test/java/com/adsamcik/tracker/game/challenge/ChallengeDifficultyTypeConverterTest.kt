package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.shared.base.database.converter.ChallengeDifficultyStringTypeConverter
import com.adsamcik.tracker.shared.base.database.converter.ChallengeTypeConverter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Challenge string type converters")
class ChallengeDifficultyTypeConverterTest {
	private val difficultyConverter = ChallengeDifficultyStringTypeConverter()
	private val typeConverter = ChallengeTypeConverter()

	@Nested
	@DisplayName("Difficulty round-trip")
	inner class DifficultyRoundTrip {
		@Test
		fun `all difficulty values survive round-trip conversion`() {
			ChallengeDifficulty.entries.forEach { difficulty ->
				val stored = difficultyConverter.fromDifficulty(difficulty)
				val restored = difficultyConverter.toDifficulty(stored)
				restored shouldBe difficulty
			}
		}

		@Test
		fun `VERY_EASY maps to stable name`() {
			difficultyConverter.fromDifficulty(ChallengeDifficulty.VERY_EASY) shouldBe "VERY_EASY"
		}

		@Test
		fun `invalid difficulty name throws`() {
			assertThrows<IllegalArgumentException> {
				difficultyConverter.toDifficulty("IMPOSSIBLE")
			}
		}
	}

	@Nested
	@DisplayName("ChallengeType round-trip")
	inner class TypeRoundTrip {
		@Test
		fun `all challenge types survive round-trip conversion`() {
			ChallengeType.entries.forEach { type ->
				val stored = typeConverter.fromChallengeType(type)
				val restored = typeConverter.toChallengeType(stored)
				restored shouldBe type
			}
		}

		@Test
		fun `Explorer maps to stable name`() {
			typeConverter.fromChallengeType(ChallengeType.Explorer) shouldBe "Explorer"
		}

		@Test
		fun `invalid challenge type name throws`() {
			assertThrows<IllegalArgumentException> {
				typeConverter.toChallengeType("Unknown")
			}
		}
	}
}
