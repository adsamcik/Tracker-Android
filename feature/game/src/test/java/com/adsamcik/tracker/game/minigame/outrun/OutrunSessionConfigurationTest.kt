package com.adsamcik.tracker.game.minigame.outrun

import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBestComparison
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.adsamcik.tracker.game.minigame.OutrunGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutrunSessionConfigurationTest {

	@Test
	fun `difficulty selects ghost pace and selected goal is exposed in snapshot`() {
		val easy = OutrunSession(
			configuration = OutrunConfiguration(OutrunGoal.METERS_100, MiniGameDifficulty.EASY),
		)
		val hard = OutrunSession(
			configuration = OutrunConfiguration(OutrunGoal.METERS_100, MiniGameDifficulty.HARD),
		)

		listOf(easy, hard).forEach { session ->
			session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
			session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 10_000L)
			session.onLocationUpdate(51.0002, 14.0, 1f, 5f, 20_000L)
		}

		assertTrue(easy.snapshot.visualPayload.currentGapMeters() > hard.snapshot.visualPayload.currentGapMeters())
		val goalProgress = assertType<MiniGameGoalProgress.Tracked>(easy.snapshot.goalProgress)
		assertEquals(100.0, goalProgress.target)
	}

	@Test
	fun `snapshot tracks gap best personal best and edge triggered feedback`() {
		val session = OutrunSession(
			configuration = OutrunConfiguration(OutrunGoal.METERS_100, MiniGameDifficulty.EASY),
			personalBestBeforeRun = 20.0,
			ghostPaceMps = 1.0,
		)

		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 1_000L)
		assertNull(session.snapshot.latestFeedback)
		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 2_000L)
		val warningFeedback = session.snapshot.latestFeedback
		session.onLocationUpdate(51.0003, 14.0, 1f, 5f, 3_000L)
		val personalBestFeedback = session.snapshot.latestFeedback

		val payload = assertType<MiniGameVisualPayload.Outrun>(session.snapshot.visualPayload)
		assertTrue(payload.bestThisRunMeters >= payload.currentGapMeters)
		assertEquals(20.0, payload.personalBestMeters)
		assertEquals(MiniGamePersonalBestComparison.AHEAD, session.snapshot.personalBest.comparison)
		assertType<MiniGameFeedbackCue.OutrunDanger>(warningFeedback!!.cue)
		assertType<MiniGameFeedbackCue.PersonalBestCrossed>(personalBestFeedback!!.cue)
		assertTrue(personalBestFeedback.eventId.value > warningFeedback.eventId.value)
		assertEquals(personalBestFeedback, session.snapshot.latestFeedback)
	}

	@Test
	fun `goal completion is naturally surfaced without persistence`() {
		val session = OutrunSession(
			configuration = OutrunConfiguration(OutrunGoal.METERS_25, MiniGameDifficulty.EASY),
			ghostPaceMps = 0.0,
		)

		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		session.onLocationUpdate(51.0003, 14.0, 1f, 5f, 1_000L)

		assertTrue(session.snapshot.goalProgress.let { it as MiniGameGoalProgress.Tracked }.isReached)
		assertType<MiniGameFeedbackCue.GoalReached>(session.snapshot.latestFeedback!!.cue)
	}

	@Test
	fun `runtime active elapsed excludes paused wall clock gaps from ghost pace`() {
		val session = OutrunSession(ghostPaceMps = 1.0)
		session.onActiveElapsedTimeChanged(0L)
		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		session.onActiveElapsedTimeChanged(1_000L)
		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 1_000L)
		val gapAtRaceStart = session.snapshot.visualPayload.currentGapMeters()
		session.onActiveElapsedTimeChanged(2_000L)
		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 10_000L)

		assertEquals(
			gapAtRaceStart - 1.0,
			session.snapshot.visualPayload.currentGapMeters(),
			0.001,
		)
	}

	@Test
	fun `warning and caught feedback are emitted once at their transitions`() {
		val warningSession = OutrunSession(ghostPaceMps = 1.0)
		warningSession.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		warningSession.onLocationUpdate(51.0001, 14.0, 1f, 5f, 10_000L)
		assertNull(warningSession.snapshot.latestFeedback)
		warningSession.onLocationUpdate(51.0001, 14.0, 1f, 5f, 11_000L)
		val warningEventId = warningSession.snapshot.latestFeedback!!.eventId
		warningSession.onLocationUpdate(51.0001, 14.0, 1f, 5f, 12_000L)
		assertEquals(warningEventId, warningSession.snapshot.latestFeedback!!.eventId)

		val caughtSession = OutrunSession(ghostPaceMps = 1.0)
		caughtSession.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		caughtSession.onLocationUpdate(51.0001, 14.0, 1f, 5f, 1_000L)
		caughtSession.onLocationUpdate(51.0001, 14.0, 1f, 5f, 20_000L)
		assertType<MiniGameFeedbackCue.SessionCompleted>(caughtSession.snapshot.latestFeedback!!.cue)
	}

	@Test
	fun `small but growing lead stays active until the player loses ground`() {
		val session = OutrunSession(ghostPaceMps = 1.0)
		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		session.onLocationUpdate(51.00005, 14.0, 1f, 5f, 1_000L)

		assertEquals(com.adsamcik.tracker.game.minigame.MiniGameState.RUNNING, session.state)
		assertNull(session.snapshot.latestFeedback)

		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 2_000L)

		assertEquals(com.adsamcik.tracker.game.minigame.MiniGameState.RUNNING, session.state)
		assertNull(session.snapshot.latestFeedback)

		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 3_000L)

		assertEquals(com.adsamcik.tracker.game.minigame.MiniGameState.WARNING, session.state)
		assertType<MiniGameFeedbackCue.OutrunDanger>(session.snapshot.latestFeedback!!.cue)
	}

	@Test
	fun `race waits for meaningful player movement before ghost starts`() {
		val session = OutrunSession(ghostPaceMps = 1.0)
		session.onActiveElapsedTimeChanged(0L)
		session.onLocationUpdate(51.0, 14.0, 0f, 5f, 0L)
		session.onActiveElapsedTimeChanged(5_000L)
		session.onLocationUpdate(51.0, 14.0, 0f, 5f, 5_000L)

		assertEquals(com.adsamcik.tracker.game.minigame.MiniGameState.RUNNING, session.state)
		assertEquals(0.0, session.snapshot.visualPayload.currentGapMeters(), 0.001)

		session.onActiveElapsedTimeChanged(6_000L)
		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 6_000L)

		assertTrue(session.snapshot.visualPayload.currentGapMeters() > 0.0)
		assertTrue((session.snapshot.visualPayload as MiniGameVisualPayload.Outrun).hasRaceStarted)
	}

	@Test
	fun `points require the same meaningful movement that starts the race`() {
		val session = OutrunSession(ghostPaceMps = 0.0)
		session.onLocationUpdate(51.0, 14.0, 0f, 5f, 0L)
		session.onLocationUpdate(51.0, 14.0, 0f, 5f, 1_000L)

		assertEquals(0, session.calculatePoints())

		session.onLocationUpdate(51.0001, 14.0, 1f, 5f, 2_000L)

		assertTrue(session.calculatePoints() > 0)
	}

	private fun MiniGameVisualPayload.currentGapMeters(): Double =
		(this as MiniGameVisualPayload.Outrun).currentGapMeters

	private inline fun <reified T> assertType(value: Any?): T {
		assertTrue(value is T)
		@Suppress("UNCHECKED_CAST")
		return value as T
	}
}
