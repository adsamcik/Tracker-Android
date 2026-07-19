package com.adsamcik.tracker.game.minigame.zenwalk

import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.ZenGoal
import com.adsamcik.tracker.game.minigame.ZenWalkConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZenWalkSessionConfigurationTest {

	@Test
	fun `difficulty changes only zone width and snapshot exposes the zone`() {
		val easy = ZenWalkSession(
			configuration = ZenWalkConfiguration(ZenGoal.MINUTES_5, MiniGameDifficulty.EASY),
			fixedTargetMps = 1.4,
		)
		val hard = ZenWalkSession(
			configuration = ZenWalkConfiguration(ZenGoal.MINUTES_5, MiniGameDifficulty.HARD),
			fixedTargetMps = 1.4,
		)
		listOf(easy, hard).forEach { session ->
			session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 0L)
			session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 5_000L)
		}

		val easyZone = (easy.snapshot.visualPayload as MiniGameVisualPayload.ZenWalk).targetPaceZone!!
		val hardZone = (hard.snapshot.visualPayload as MiniGameVisualPayload.ZenWalk).targetPaceZone!!
		assertTrue(easyZone.maximumMetersPerSecond - easyZone.minimumMetersPerSecond >
			hardZone.maximumMetersPerSecond - hardZone.minimumMetersPerSecond)
		assertEquals(300.0, (easy.snapshot.goalProgress as MiniGameGoalProgress.Tracked).target)
	}

	@Test
	fun `calibration and explicit runtime elapsed are reflected without wall clock pause gap`() {
		val session = ZenWalkSession(fixedTargetMps = 1.4)
		session.onActiveElapsedTimeChanged(0L)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 0L)
		session.onActiveElapsedTimeChanged(5_000L)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 5_000L)
		session.onActiveElapsedTimeChanged(6_000L)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 60_000L)

		val payload = assertType<MiniGameVisualPayload.ZenWalk>(session.snapshot.visualPayload)
		assertEquals(1.0, payload.calibrationProgress)
		assertEquals(6_000L, payload.timeInZoneMs)
		assertEquals(6_000L, session.snapshot.elapsedActiveTimeMs)
	}

	@Test
	fun `zone feedback is suppressed during short boundary flapping`() {
		val session = ZenWalkSession(fixedTargetMps = 1.4)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 0L)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 5_000L)
		assertNull(session.snapshot.latestFeedback)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 10_000L)
		assertType<MiniGameFeedbackCue.ZenZoneEntered>(session.snapshot.latestFeedback!!.cue)
		val eventId = session.snapshot.latestFeedback!!.eventId
		session.onLocationUpdate(51.0, 14.0, 0.1f, 5f, 11_000L)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 12_000L)

		assertEquals(eventId, session.snapshot.latestFeedback!!.eventId)
	}

	@Test
	fun `selected time goal completes naturally`() {
		val session = ZenWalkSession(
			configuration = ZenWalkConfiguration(ZenGoal.MINUTES_5, MiniGameDifficulty.NORMAL),
			fixedTargetMps = 1.4,
		)
		session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, 0L)
		repeat(60) { index ->
			session.onLocationUpdate(51.0, 14.0, 1.4f, 5f, (index + 1) * 5_000L)
		}

		assertTrue((session.snapshot.goalProgress as MiniGameGoalProgress.Tracked).isReached)
		assertType<MiniGameFeedbackCue.GoalReached>(session.snapshot.latestFeedback!!.cue)
	}

	private inline fun <reified T> assertType(value: Any?): T {
		assertTrue(value is T)
		@Suppress("UNCHECKED_CAST")
		return value as T
	}
}
