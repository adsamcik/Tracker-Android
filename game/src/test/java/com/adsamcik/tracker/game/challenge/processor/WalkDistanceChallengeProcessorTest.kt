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

@DisplayName("WalkDistanceChallengeProcessor")
class WalkDistanceChallengeProcessorTest {

	private val processor = WalkDistanceChallengeProcessor()

	@Nested
	@DisplayName("Type and defaults")
	inner class TypeAndDefaults {
		@Test
		fun `type is WalkDistance`() {
			processor.type shouldBe ChallengeType.WalkDistance
		}

		@Test
		fun `default required value is 30000`() {
			processor.defaultRequiredValue shouldBe 30_000.0
		}

		@Test
		fun `default duration is 7 days`() {
			processor.defaultDurationMs shouldBe 7L * 24 * 60 * 60 * 1000
		}
	}

	@Nested
	@DisplayName("extractProgress")
	inner class ExtractProgress {
		@Test
		fun `returns distance on foot as double`() = runTest {
			val context: Context = mockk()
			val session = createSession(distanceOnFoot = 5432.5f)
			processor.extractProgress(context, session) shouldBe 5432.5.toDouble()
		}

		@Test
		fun `returns 0 when no distance`() = runTest {
			val context: Context = mockk()
			val session = createSession(distanceOnFoot = 0f)
			processor.extractProgress(context, session) shouldBe 0.0
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
