package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.processor.ActiveTimeChallengeProcessor
import com.adsamcik.tracker.game.challenge.processor.ExplorerChallengeProcessor
import com.adsamcik.tracker.game.challenge.processor.StepChallengeProcessor
import com.adsamcik.tracker.game.challenge.processor.WalkDistanceChallengeProcessor
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor] implementations.
 */
@DisplayName("ChallengeProcessor implementations")
class ChallengeDefinitionTest {

	@Nested
	@DisplayName("StepChallengeProcessor")
	inner class StepProcessor {

		private val processor = StepChallengeProcessor()

		@Test
		fun `type is Step`() {
			processor.type shouldBe ChallengeType.Step
		}

		@Test
		fun `defaultRequiredValue is positive`() {
			processor.defaultRequiredValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `defaultDurationMs is positive`() {
			processor.defaultDurationMs shouldBeGreaterThan 0L
		}

		@Test
		fun `min duration multiplier is less than max`() {
			processor.minDurationMultiplier shouldBeLessThan processor.maxDurationMultiplier
		}
	}

	@Nested
	@DisplayName("ActiveTimeChallengeProcessor")
	inner class ActiveTimeProcessor {

		private val processor = ActiveTimeChallengeProcessor()

		@Test
		fun `type is ActiveTime`() {
			processor.type shouldBe ChallengeType.ActiveTime
		}

		@Test
		fun `defaultRequiredValue is positive`() {
			processor.defaultRequiredValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `defaultDurationMs is positive`() {
			processor.defaultDurationMs shouldBeGreaterThan 0L
		}
	}

	@Nested
	@DisplayName("ExplorerChallengeProcessor")
	inner class ExplorerProcessor {

		private val processor = ExplorerChallengeProcessor()

		@Test
		fun `type is Explorer`() {
			processor.type shouldBe ChallengeType.Explorer
		}

		@Test
		fun `defaultRequiredValue is positive`() {
			processor.defaultRequiredValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `defaultDurationMs is positive`() {
			processor.defaultDurationMs shouldBeGreaterThan 0L
		}
	}

	@Nested
	@DisplayName("WalkDistanceChallengeProcessor")
	inner class WalkDistanceProcessor {

		private val processor = WalkDistanceChallengeProcessor()

		@Test
		fun `type is WalkDistance`() {
			processor.type shouldBe ChallengeType.WalkDistance
		}

		@Test
		fun `defaultRequiredValue is positive`() {
			processor.defaultRequiredValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `defaultDurationMs is positive`() {
			processor.defaultDurationMs shouldBeGreaterThan 0L
		}
	}
}
