package com.adsamcik.tracker.game.challenge.processor

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewProcessorTest {

	// --- Speed Challenge ---

	@Test
	fun `speed processor has correct type`() {
		val processor = SpeedChallengeProcessor()
		assertEquals(ChallengeType.Speed, processor.type)
	}

	@Test
	fun `speed processor extracts distance on foot`() = runTest {
		val processor = SpeedChallengeProcessor()
		val context: Context = mockk()
		val session = createSession(distanceOnFoot = 1500f)
		assertEquals(1500.0, processor.extractProgress(context, session))
	}

	@Test
	fun `speed processor default values are reasonable`() {
		val processor = SpeedChallengeProcessor()
		assertEquals(2_000.0, processor.defaultRequiredValue)
		assertEquals(24L * 60 * 60 * 1000, processor.defaultDurationMs)
	}

	// --- Consistency Challenge ---

	@Test
	fun `consistency processor has correct type`() {
		val processor = ConsistencyChallengeProcessor()
		assertEquals(ChallengeType.Consistency, processor.type)
	}

	@Test
	fun `consistency adds unique day from session`() {
		val processor = ConsistencyChallengeProcessor()
		val entity = createEntity(type = ChallengeType.Consistency, required = 7.0)
		val session = createSession(
			start = 86_400_000L * 19950,
			collections = 5,
		)
		val updated = processor.updateEntity(entity, session)
		assertEquals(1.0, updated.currentValue)
		assertTrue(updated.extraJson?.contains("19950") == true)
	}

	@Test
	fun `consistency ignores session with less than 2 collections`() {
		val processor = ConsistencyChallengeProcessor()
		val entity = createEntity(type = ChallengeType.Consistency, required = 7.0)
		val session = createSession(start = 86_400_000L * 19950, collections = 1)
		val updated = processor.updateEntity(entity, session)
		assertEquals(0.0, updated.currentValue)
	}

	@Test
	fun `consistency does not double count same day`() {
		val processor = ConsistencyChallengeProcessor()
		val entity = createEntity(type = ChallengeType.Consistency, required = 7.0)
		val session1 = createSession(start = 86_400_000L * 19950, collections = 3)
		val updated1 = processor.updateEntity(entity, session1)
		assertEquals(1.0, updated1.currentValue)

		val session2 = createSession(start = 86_400_000L * 19950 + 3600_000, collections = 3)
		val updated2 = processor.updateEntity(updated1, session2)
		assertEquals(1.0, updated2.currentValue)
	}

	@Test
	fun `consistency counts multiple distinct days`() {
		val processor = ConsistencyChallengeProcessor()
		var entity = createEntity(type = ChallengeType.Consistency, required = 7.0)
		for (day in 19950L..19953L) {
			val session = createSession(start = 86_400_000L * day, collections = 5)
			entity = processor.updateEntity(entity, session)
		}
		assertEquals(4.0, entity.currentValue)
	}

	@Test
	fun `consistency default values are reasonable`() {
		val processor = ConsistencyChallengeProcessor()
		assertEquals(7.0, processor.defaultRequiredValue)
		assertEquals(10L * 24 * 60 * 60 * 1000, processor.defaultDurationMs)
	}

	// --- Helpers ---

	private fun createSession(
		distanceOnFoot: Float = 0f,
		start: Long = 0L,
		collections: Int = 10,
	): TrackerSession {
		return TrackerSession(
			id = 1L,
			start = start,
			end = start + 3600_000,
			isUserInitiated = true,
			collections = collections,
			distanceInM = distanceOnFoot,
			distanceOnFootInM = distanceOnFoot,
			distanceInVehicleInM = 0f,
			steps = 0,
			sessionActivityId = null,
		)
	}

	private fun createEntity(
		type: ChallengeType = ChallengeType.Speed,
		required: Double = 2000.0,
	): ChallengeEntity {
		return ChallengeEntity(
			id = 1L,
			type = type,
			difficulty = ChallengeDifficulty.MEDIUM,
			startTime = 0L,
			endTime = 86_400_000L * 30,
			requiredValue = required,
			currentValue = 0.0,
			extraJson = null,
			isCompleted = false,
		)
	}
}
