package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SpeedChallengeProcessor")
class SpeedChallengeProcessorTest {

	private val processor = SpeedChallengeProcessor()

	@Nested
	@DisplayName("Type and defaults")
	inner class TypeAndDefaults {
		@Test
		fun `type is Speed`() {
			processor.type shouldBe ChallengeType.Speed
		}

		@Test
		fun `default required value is 2000`() {
			processor.defaultRequiredValue shouldBe 2_000.0
		}

		@Test
		fun `default duration is 1 day`() {
			processor.defaultDurationMs shouldBe 24L * 60 * 60 * 1000
		}

		@Test
		fun `duration multiplier range is tighter than default`() {
			processor.minDurationMultiplier shouldBe 0.5
			processor.maxDurationMultiplier shouldBe 2.0
		}
	}

	@Nested
	@DisplayName("extractProgress")
	inner class ExtractProgress {
		@Test
		fun `returns distance on foot as double`() = runTest {
			val context: Context = mockk()
			val session = createSession(distanceOnFoot = 1500f)
			processor.extractProgress(context, session) shouldBe 1500.0
		}
	}

	private fun createSession(distanceOnFoot: Float = 0f): TrackerSession {
		return TrackerSession(
			id = 1L,
			start = 0L,
			end = 3600_000L,
			isUserInitiated = true,
			collections = 10,
			distanceInM = distanceOnFoot,
			distanceOnFootInM = distanceOnFoot,
			distanceInVehicleInM = 0f,
			steps = 0,
			sessionActivityId = null,
		)
	}
}
