package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ConsistencyChallengeProcessor")
class ConsistencyChallengeProcessorTest {

	private val processor = ConsistencyChallengeProcessor()

	@Nested
	@DisplayName("Type and defaults")
	inner class TypeAndDefaults {
		@Test
		fun `type is Consistency`() {
			processor.type shouldBe ChallengeType.Consistency
		}

		@Test
		fun `default required value is 7 days`() {
			processor.defaultRequiredValue shouldBe 7.0
		}

		@Test
		fun `default duration is 10 days`() {
			processor.defaultDurationMs shouldBe 10L * 24 * 60 * 60 * 1000
		}

		@Test
		fun `duration multiplier range is tighter`() {
			processor.minDurationMultiplier shouldBe 0.7
			processor.maxDurationMultiplier shouldBe 2.0
		}
	}

	@Nested
	@DisplayName("extractProgress")
	inner class ExtractProgress {
		@Test
		fun `always returns 0 because progress is handled by updateEntity`() = runTest {
			val context: Context = mockk()
			val session = createSession(collections = 10)
			processor.extractProgress(context, session) shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("updateEntity")
	inner class UpdateEntity {
		@Test
		fun `adds unique day from session`() {
			val entity = createEntity()
			val session = createSession(start = 86_400_000L * 100, collections = 5)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 1.0
			updated.extraJson!! shouldContain "100"
		}

		@Test
		fun `ignores session with fewer than 2 collections`() {
			val entity = createEntity()
			val session = createSession(start = 86_400_000L * 100, collections = 1)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 0.0
		}

		@Test
		fun `exactly 2 collections are accepted`() {
			val entity = createEntity()
			val session = createSession(start = 86_400_000L * 100, collections = 2)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 1.0
		}

		@Test
		fun `does not double count same day`() {
			val entity = createEntity()
			val session1 = createSession(start = 86_400_000L * 100, collections = 5)
			val updated1 = processor.updateEntity(entity, session1)
			val session2 = createSession(start = 86_400_000L * 100 + 3600_000, collections = 5)
			val updated2 = processor.updateEntity(updated1, session2)
			updated2.currentValue shouldBe 1.0
		}

		@Test
		fun `counts multiple distinct days`() {
			var entity = createEntity()
			for (day in 100L..104L) {
				val session = createSession(start = 86_400_000L * day, collections = 5)
				entity = processor.updateEntity(entity, session)
			}
			entity.currentValue shouldBe 5.0
		}

		@Test
		fun `handles pre-existing extraJson`() {
			val entity = createEntity().copy(
				currentValue = 2.0,
				extraJson = """{"trackedDays":[100,101]}""",
			)
			val session = createSession(start = 86_400_000L * 102, collections = 3)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 3.0
		}

		@Test
		fun `handles null extraJson`() {
			val entity = createEntity().copy(extraJson = null)
			val session = createSession(start = 86_400_000L * 200, collections = 3)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 1.0
		}

		@Test
		fun `handles empty extraJson`() {
			val entity = createEntity().copy(extraJson = "")
			val session = createSession(start = 86_400_000L * 200, collections = 3)
			val updated = processor.updateEntity(entity, session)
			updated.currentValue shouldBe 1.0
		}
	}

	private fun createSession(
		start: Long = 0L,
		collections: Int = 10,
	): TrackerSession {
		return TrackerSession(
			id = 1L,
			start = start,
			end = start + 3600_000,
			isUserInitiated = true,
			collections = collections,
			distanceInM = 0f,
			distanceOnFootInM = 0f,
			distanceInVehicleInM = 0f,
			steps = 0,
			sessionActivityId = null,
		)
	}

	private fun createEntity(): ChallengeEntity {
		return ChallengeEntity(
			id = 1L,
			type = ChallengeType.Consistency,
			difficulty = ChallengeDifficulty.MEDIUM,
			startTime = 0L,
			endTime = 86_400_000L * 30,
			requiredValue = 7.0,
			currentValue = 0.0,
			extraJson = null,
			isCompleted = false,
		)
	}
}
