package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.typeconverter.ChallengeDifficultyTypeConverter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ChallengeDifficultyTypeConverter")
class ChallengeDifficultyTypeConverterTest {

	private val converter = ChallengeDifficultyTypeConverter()

	@Nested
	@DisplayName("Difficulty round-trip")
	inner class DifficultyRoundTrip {

		@Test
		fun `all difficulty values survive round-trip conversion`() {
			ChallengeDifficulty.values().forEach { difficulty ->
				val ordinal = converter.toInt(difficulty)
				val restored = converter.difficultyToEnum(ordinal)
				restored shouldBe difficulty
			}
		}

		@Test
		fun `VERY_EASY maps to ordinal 0`() {
			converter.toInt(ChallengeDifficulty.VERY_EASY) shouldBe 0
		}

		@Test
		fun `VERY_HARD maps to ordinal 4`() {
			converter.toInt(ChallengeDifficulty.VERY_HARD) shouldBe 4
		}

		@Test
		fun `invalid ordinal throws for difficulty`() {
			assertThrows<ArrayIndexOutOfBoundsException> {
				converter.difficultyToEnum(99)
			}
		}
	}

	@Nested
	@DisplayName("ChallengeType round-trip")
	inner class TypeRoundTrip {

		@Test
		fun `all challenge types survive round-trip conversion`() {
			ChallengeType.values().forEach { type ->
				val ordinal = converter.toInt(type)
				val restored = converter.typeToEnum(ordinal)
				restored shouldBe type
			}
		}

		@Test
		fun `Explorer maps to ordinal 0`() {
			converter.toInt(ChallengeType.Explorer) shouldBe 0
		}

		@Test
		fun `ActiveTime maps to ordinal 3`() {
			converter.toInt(ChallengeType.ActiveTime) shouldBe 3
		}

		@Test
		fun `invalid ordinal throws for type`() {
			assertThrows<ArrayIndexOutOfBoundsException> {
				converter.typeToEnum(99)
			}
		}
	}
}
