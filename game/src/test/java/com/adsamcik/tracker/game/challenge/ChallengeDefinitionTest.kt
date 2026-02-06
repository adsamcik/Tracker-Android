package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.definition.ActiveTimeChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.definition.StepChallengeDefinition
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for challenge definition constants and configuration.
 */
@DisplayName("ChallengeDefinition")
class ChallengeDefinitionTest {

	@Nested
	@DisplayName("StepChallengeDefinition")
	inner class StepDefinition {

		private val definition = StepChallengeDefinition()

		@Test
		fun `type is Step`() {
			definition.type shouldBe ChallengeType.Step
		}

		@Test
		fun `default required step count is positive`() {
			definition.defaultRequiredStepCount shouldBeGreaterThan 0
		}

		@Test
		fun `default duration is positive`() {
			definition.defaultDuration shouldBeGreaterThan 0L
		}

		@Test
		fun `min duration multiplier is less than max`() {
			definition.minDurationMultiplier shouldBeLessThan definition.maxDurationMultiplier
		}

		@Test
		fun `min duration multiplier is positive`() {
			definition.minDurationMultiplier shouldBeGreaterThan 0.0
		}
	}

	@Nested
	@DisplayName("ActiveTimeChallengeDefinition")
	inner class ActiveTimeDefinition {

		private val definition = ActiveTimeChallengeDefinition()

		@Test
		fun `type is ActiveTime`() {
			definition.type shouldBe ChallengeType.ActiveTime
		}

		@Test
		fun `default active time is positive`() {
			definition.defaultActiveTimeInMinutes shouldBeGreaterThan 0
		}

		@Test
		fun `default duration is positive`() {
			definition.defaultDuration shouldBeGreaterThan 0L
		}
	}

	@Nested
	@DisplayName("ChallengeDefinition companion constants")
	inner class CompanionConstants {

		@Test
		fun `MAX_DURATION_MULTIPLIER is greater than MIN`() {
			ChallengeDefinition.MAX_DURATION_MULTIPLIER shouldBeGreaterThan ChallengeDefinition.MIN_DURATION_MULTIPLIER
		}

		@Test
		fun `MIN_DURATION_MULTIPLIER is positive`() {
			ChallengeDefinition.MIN_DURATION_MULTIPLIER shouldBeGreaterThan 0.0
		}
	}
}
