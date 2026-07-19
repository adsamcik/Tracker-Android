package com.adsamcik.tracker.game.minigame.switchback

import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePersonalBestComparison
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.adsamcik.tracker.game.minigame.SwitchbackGoal
import com.adsamcik.tracker.game.minigame.SwitchbackVisualPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos

class SwitchbackSessionTest {

	@Test
	fun `idle start exposes zero progress without coordinates`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		val payload = session.visualPayload
		val progress = session.snapshot.goalProgress as MiniGameGoalProgress.Tracked

		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(MiniGamePhase.WAITING_TO_START, session.snapshot.phase)
		assertEquals(0.0, session.score)
		assertEquals(0.0, progress.current)
		assertEquals(6.0, progress.target)
		assertFalse(payload.hasFirstLeg)
		assertNull(payload.expectedTurn)
		assertTrue(payload.recentTurns.isEmpty())
	}

	@Test
	fun `goals are constrained to three supported turn presets`() {
		assertEquals(listOf(4, 6, 10), SwitchbackGoal.entries.map { it.turns })
		assertEquals(listOf(4, 6, 10), SwitchbackGoal.entries.map { it.displayValue })
		assertEquals(listOf(4.0, 6.0, 10.0), SwitchbackGoal.entries.map { it.scoreTarget })
	}

	@Test
	fun `first accurate fix starts session but does not invent a turn`() {
		val session = SwitchbackSession()

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)

		assertEquals(MiniGameState.RUNNING, session.state)
		assertEquals(0.0, session.score)
		assertFalse(session.visualPayload.hasFirstLeg)
		assertEquals(MiniGameSignalQuality.GOOD, session.snapshot.signal.quality)
	}

	@Test
	fun `snapshot exposes the typed switchback payload`() {
		val session = SwitchbackSession()

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)

		assertTrue(session.snapshot.visualPayload is SwitchbackVisualPayload)
		assertFalse(session.snapshot.visualPayload is MiniGameVisualPayload.Pending)
	}

	@Test
	fun `low accuracy fixes are rejected before the private route anchor is created`() {
		val session = SwitchbackSession()

		session.sample(eastM = 0.0, northM = 0.0, accuracyM = 45f, timestampMs = 0L)

		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(MiniGameSignalQuality.POOR, session.snapshot.signal.quality)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `vehicle speed resets leg detection without awarding progress`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		session.sample(eastM = 0.0, northM = 40.0, speedMps = 12f, timestampMs = 10_000L)
		session.sample(eastM = 40.0, northM = 40.0, timestampMs = 20_000L)

		assertEquals(0.0, session.score)
		assertTrue(session.visualPayload.hasFirstLeg)
	}

	@Test
	fun `inferred vehicle speed rejects a teleport even when reported speed is walking`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		session.sample(eastM = 0.0, northM = 40.0, speedMps = 1.5f, timestampMs = 1_000L)
		session.sample(eastM = 40.0, northM = 40.0, speedMps = 1.5f, timestampMs = 11_000L)

		assertEquals(0.0, session.score)
		assertTrue(session.visualPayload.hasFirstLeg)
	}

	@Test
	fun `equal and backwards timestamps cannot create or corrupt turns`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 10_000L)
		session.sample(eastM = 0.0, northM = 40.0, timestampMs = 20_000L)
		session.sample(eastM = 40.0, northM = 40.0, timestampMs = 20_000L)
		session.sample(eastM = 40.0, northM = 40.0, timestampMs = 19_000L)
		assertEquals(0.0, session.score)

		session.sample(eastM = 40.0, northM = 40.0, timestampMs = 30_000L)

		assertEquals(1.0, session.score)
	}

	@Test
	fun `out of range coordinates and negative timestamps are unusable fixes`() {
		val session = SwitchbackSession()

		session.onLocationUpdate(91.0, BASE_LONGITUDE, 1.5f, 5f, 0L)
		session.onLocationUpdate(BASE_LATITUDE, 181.0, 1.5f, 5f, 1_000L)
		session.onLocationUpdate(BASE_LATITUDE, BASE_LONGITUDE, 1.5f, 5f, -1L)

		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(MiniGameSignalQuality.POOR, session.snapshot.signal.quality)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `runtime active time is reflected without UI driven updates`() {
		val session = SwitchbackSession()

		session.onActiveElapsedTimeChanged(42_000L)

		assertEquals(42_000L, session.snapshot.elapsedActiveTimeMs)
		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `alternating route turns complete the configured goal`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_4))

		session.carveAlternatingTurns(turnCount = 4)

		val progress = session.snapshot.goalProgress as MiniGameGoalProgress.Tracked
		val payload = session.visualPayload
		assertEquals(4.0, session.score)
		assertTrue(progress.isReached)
		assertEquals(MiniGameState.FINISHED, session.state)
		assertTrue(payload.goalReached)
		assertEquals(4, payload.bestCombo)
		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.GoalReached)
	}

	@Test
	fun `turn feedback fires for ordinary turns but goal wins on the final turn`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_4))

		session.carveAlternatingTurns(turnCount = 1)
		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.SwitchbackTurnCarved)

		session.continueAlternatingTurns(fromTurnCount = 1, additionalTurns = 3)

		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.GoalReached)
	}

	@Test
	fun `two consecutive turns in the same direction reset flow combo to one`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		session.sample(eastM = 0.0, northM = 40.0, timestampMs = 10_000L)
		session.sample(eastM = 40.0, northM = 40.0, timestampMs = 20_000L)
		session.sample(eastM = 40.0, northM = 0.0, timestampMs = 30_000L)

		assertEquals(2.0, session.score)
		assertEquals(1, session.visualPayload.currentCombo)
		assertEquals(1, session.visualPayload.bestCombo)
		assertEquals(SwitchbackTurnDirection.LEFT, session.visualPayload.expectedTurn)
	}

	@Test
	fun `sub threshold angle establishes a new leg without scoring a turn`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_6))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		session.sample(eastM = 0.0, northM = 40.0, timestampMs = 10_000L)
		session.sample(eastM = 28.3, northM = 68.3, timestampMs = 20_000L)

		assertEquals(0.0, session.score)
		assertTrue(session.visualPayload.hasFirstLeg)
		assertNull(session.visualPayload.expectedTurn)
	}

	@Test
	fun `personal best crossing is edge triggered exactly once`() {
		val session = SwitchbackSession(
			configuration = configuration(SwitchbackGoal.TURNS_10),
			personalBestBeforeRun = 1.0,
		)

		session.carveAlternatingTurns(turnCount = 2)
		val crossing = requireNotNull(session.snapshot.latestFeedback)
		assertTrue(crossing.cue is MiniGameFeedbackCue.PersonalBestCrossed)
		assertEquals(MiniGamePersonalBestComparison.AHEAD, session.snapshot.personalBest.comparison)

		session.continueAlternatingTurns(fromTurnCount = 2, additionalTurns = 2)

		val nextFeedback = requireNotNull(session.snapshot.latestFeedback)
		assertTrue(nextFeedback.cue is MiniGameFeedbackCue.SwitchbackTurnCarved)
		assertTrue(nextFeedback.eventId.value > crossing.eventId.value)
		assertEquals(4.0, session.score)
	}

	@Test
	fun `points require at least one measured turn rather than mere location fixes`() {
		val session = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_10))

		session.sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		session.sample(eastM = 0.0, northM = 40.0, timestampMs = 10_000L)
		session.onSessionEnd()

		assertEquals(0, session.calculatePoints())
	}

	@Test
	fun `points scale with turns and combo while combo bonus caps`() {
		val shortRun = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_10))
		shortRun.carveAlternatingTurns(turnCount = 2)
		shortRun.onSessionEnd()

		val cappedRun = SwitchbackSession(configuration = configuration(SwitchbackGoal.TURNS_10))
		cappedRun.carveAlternatingTurns(turnCount = 10)
		cappedRun.onSessionEnd()

		assertEquals(44, shortRun.calculatePoints())
		assertEquals(132, cappedRun.calculatePoints())
	}

	private fun configuration(goal: SwitchbackGoal) = SwitchbackConfiguration(
		goal = goal,
		difficulty = MiniGameDifficulty.NORMAL,
	)

	private fun SwitchbackSession.carveAlternatingTurns(turnCount: Int) {
		sample(eastM = 0.0, northM = 0.0, timestampMs = 0L)
		sample(eastM = 0.0, northM = LEG_METERS, timestampMs = 10_000L)
		continueAlternatingTurns(fromTurnCount = 0, additionalTurns = turnCount)
	}

	private fun SwitchbackSession.continueAlternatingTurns(
		fromTurnCount: Int,
		additionalTurns: Int,
	) {
		for (turnIndex in fromTurnCount until fromTurnCount + additionalTurns) {
			val step = turnIndex + 1
			val east = ((step + 1) / 2) * LEG_METERS
			val north = (1 + step / 2) * LEG_METERS
			sample(
				eastM = east,
				northM = north,
				timestampMs = (step + 1) * 10_000L,
			)
		}
	}

	private fun SwitchbackSession.sample(
		eastM: Double,
		northM: Double,
		speedMps: Float = 1.5f,
		accuracyM: Float = 5f,
		timestampMs: Long,
	) {
		val latitude = BASE_LATITUDE + northM / METERS_PER_DEGREE_LATITUDE
		val longitude = BASE_LONGITUDE +
			eastM / (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(BASE_LATITUDE)))
		onLocationUpdate(latitude, longitude, speedMps, accuracyM, timestampMs)
	}

	private companion object {
		const val BASE_LATITUDE = 51.0
		const val BASE_LONGITUDE = 14.0
		const val METERS_PER_DEGREE_LATITUDE = 111_195.0
		const val LEG_METERS = 40.0
	}
}
