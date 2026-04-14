package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("StepChallengeProcessor")
class StepChallengeProcessorTest {

	private val processor = StepChallengeProcessor()

	@Nested
	@DisplayName("Type and defaults")
	inner class TypeAndDefaults {
		@Test
		fun `type is Step`() {
			processor.type shouldBe ChallengeType.Step
		}

		@Test
		fun `default required value is 50000`() {
			processor.defaultRequiredValue shouldBe 50_000.0
		}

		@Test
		fun `default duration is 7 days`() {
			processor.defaultDurationMs shouldBe 7L * 24 * 60 * 60 * 1000
		}

		@Test
		fun `default duration multiplier range`() {
			processor.minDurationMultiplier shouldBe 0.25
			processor.maxDurationMultiplier shouldBe 3.0
		}
	}

	@Nested
	@DisplayName("extractProgress")
	inner class ExtractProgress {
		@Test
		fun `returns session step count as double`() = runTest {
			val context: Context = mockk()
			val session = createSession(steps = 1234)
			processor.extractProgress(context, session) shouldBe 1234.0
		}

		@Test
		fun `returns 0 when no steps`() = runTest {
			val context: Context = mockk()
			val session = createSession(steps = 0)
			processor.extractProgress(context, session) shouldBe 0.0
		}
	}

	private fun createSession(steps: Int = 0): TrackerSession {
		return TrackerSession(
			id = 1L,
			start = 0L,
			end = 3600_000L,
			isUserInitiated = true,
			collections = 10,
			distanceInM = 0f,
			distanceOnFootInM = 0f,
			distanceInVehicleInM = 0f,
			steps = steps,
			sessionActivityId = null,
		)
	}
}
