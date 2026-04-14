package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ActiveTimeChallengeProcessor")
class ActiveTimeChallengeProcessorTest {

	private val processor = ActiveTimeChallengeProcessor()

	@Nested
	@DisplayName("Type and defaults")
	inner class TypeAndDefaults {
		@Test
		fun `type is ActiveTime`() {
			processor.type shouldBe ChallengeType.ActiveTime
		}

		@Test
		fun `default required value is 300 minutes`() {
			processor.defaultRequiredValue shouldBe 300.0
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
		fun `estimates active time from distance`() = runTest {
			val context: Context = mockk()
			// 1400m in 1 hour -> 1400/1.4 = 1000s -> ~16.67 min
			val session = createSession(distanceOnFoot = 1400f, durationMs = 3600_000L)
			val progress = processor.extractProgress(context, session)
			progress shouldBeGreaterThan 16.0
			progress shouldBeLessThanOrEqual 17.0
		}

		@Test
		fun `active time is capped by session duration`() = runTest {
			val context: Context = mockk()
			// Very large distance but short session: should be capped
			val session = createSession(distanceOnFoot = 100_000f, durationMs = 60_000L)
			val progress = processor.extractProgress(context, session)
			// Capped at 60s / 60 = 1.0 min
			progress shouldBe 1.0
		}

		@Test
		fun `returns 0 when no distance`() = runTest {
			val context: Context = mockk()
			val session = createSession(distanceOnFoot = 0f, durationMs = 3600_000L)
			val progress = processor.extractProgress(context, session)
			progress shouldBe 0.0
		}
	}

	private fun createSession(distanceOnFoot: Float = 0f, durationMs: Long = 3600_000L): TrackerSession {
		return TrackerSession(
			id = 1L,
			start = 1000L,
			end = 1000L + durationMs,
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
