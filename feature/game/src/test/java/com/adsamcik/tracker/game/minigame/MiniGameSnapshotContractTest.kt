package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.switchback.SwitchbackTurnDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniGameSnapshotContractTest {

	@Test
	fun `territory payload accepts only origin-relative cells inside the seven by seven grid`() {
		val origin = TerritoryRelativeCell(rowOffset = 0, columnOffset = 0)
		val edge = TerritoryRelativeCell(rowOffset = 3, columnOffset = -3)
		val claimedCells = mutableSetOf(origin, edge)
		val recentTrail = mutableListOf(origin, edge)
		val payload = MiniGameVisualPayload.Territory(
			claimedCells = claimedCells,
			currentCell = edge,
			recentTrail = recentTrail,
		)

		claimedCells.clear()
		recentTrail.clear()
		assertEquals(setOf(origin, edge), payload.claimedCells)
		assertEquals(listOf(origin, edge), payload.recentTrail)
		assertThrows(UnsupportedOperationException::class.java) {
			@Suppress("UNCHECKED_CAST")
			(payload.claimedCells as MutableSet<TerritoryRelativeCell>).clear()
		}
		assertThrows(IllegalArgumentException::class.java) {
			TerritoryRelativeCell(rowOffset = 4, columnOffset = 0)
		}
		assertThrows(IllegalArgumentException::class.java) {
			TerritoryRelativeCell(rowOffset = 0, columnOffset = -4)
		}
	}

	@Test
	fun `visual payloads validate bounded and finite display values`() {
		MiniGameVisualPayload.Outrun(
			currentGapMeters = -5.0,
			warningThresholdMeters = 20.0,
			bestThisRunMeters = 10.0,
			personalBestMeters = 25.0,
		)
		MiniGameVisualPayload.ZenWalk(
			calibrationProgress = 0.5,
			currentSmoothedPaceMetersPerSecond = 1.4,
			targetPaceZone = ZenPaceZone(1.2, 1.6),
			timeInZoneMs = 30_000L,
		)
		FuseRunVisualPayload(
			hasStarted = true,
			defusedCharges = 1,
			targetCharges = 5,
			roundNumber = 2,
			distanceFromChargeMeters = 12.0,
			requiredDistanceMeters = 22.0,
			remainingTimeMs = 10_000L,
			roundDurationMs = 30_000L,
			currentStreak = 1,
			bestStreak = 1,
		)
		SwitchbackVisualPayload(
			turnsCompleted = 1,
			targetTurns = 6,
			currentCombo = 1,
			bestCombo = 1,
			expectedTurn = SwitchbackTurnDirection.RIGHT,
			recentTurns = listOf(SwitchbackTurnDirection.LEFT),
			hasFirstLeg = true,
			minimumLegMeters = 32.0,
			goalReached = false,
		)

		assertThrows(IllegalArgumentException::class.java) {
			MiniGameVisualPayload.Outrun(
				currentGapMeters = Double.NaN,
				warningThresholdMeters = 20.0,
				bestThisRunMeters = 0.0,
				personalBestMeters = null,
			)
		}
		assertThrows(IllegalArgumentException::class.java) {
			MiniGameVisualPayload.ZenWalk(
				calibrationProgress = 1.1,
				currentSmoothedPaceMetersPerSecond = null,
				targetPaceZone = null,
				timeInZoneMs = 0L,
			)
		}
	}

	@Test
	fun `personal best comparison distinguishes first ahead tied and behind`() {
		assertEquals(
			MiniGamePersonalBestComparison.FIRST_RUN,
			MiniGamePersonalBest.compare(currentScore = 4.0, scoreBeforeRun = null).comparison,
		)
		assertEquals(
			MiniGamePersonalBestComparison.AHEAD,
			MiniGamePersonalBest.compare(currentScore = 6.0, scoreBeforeRun = 5.0).comparison,
		)
		assertEquals(
			MiniGamePersonalBestComparison.TIED,
			MiniGamePersonalBest.compare(currentScore = 5.0, scoreBeforeRun = 5.0).comparison,
		)
		assertEquals(
			MiniGamePersonalBestComparison.BEHIND,
			MiniGamePersonalBest.compare(currentScore = 4.0, scoreBeforeRun = 5.0).comparison,
		)
	}

	@Test
	fun `base session accepts monotonic active elapsed time and emits monotonic feedback ids`() {
		val session = ContractFakeSession()
		val pointsBefore = session.calculatePoints()

		session.onActiveElapsedTimeChanged(1_000L)
		assertEquals(1_000L, session.snapshot.elapsedActiveTimeMs)
		assertEquals(pointsBefore, session.calculatePoints())
		assertNull(session.snapshot.latestFeedback)

		session.emit(MiniGameFeedbackCue.OutrunDanger)
		val firstId = session.snapshot.latestFeedback!!.eventId.value
		session.emit(MiniGameFeedbackCue.PersonalBestCrossed)
		val secondId = session.snapshot.latestFeedback!!.eventId.value

		assertTrue(secondId > firstId)
		assertThrows(IllegalArgumentException::class.java) {
			session.onActiveElapsedTimeChanged(999L)
		}
	}

	private class ContractFakeSession : MiniGameSession(
		configuration = OutrunConfiguration(OutrunGoal.METERS_25, MiniGameDifficulty.NORMAL),
		personalBestBeforeRun = 10.0,
	) {
		override val state: MiniGameState = MiniGameState.RUNNING
		override val score: Double = 12.0
		override val statusText: String = "compatibility"

		override fun onLocationUpdate(
			latitude: Double,
			longitude: Double,
			speedMps: Float,
			accuracyM: Float,
			timestampMs: Long,
		) = Unit

		override fun onSessionEnd() = Unit

		override fun calculatePoints(): Int = 42

		fun emit(cue: MiniGameFeedbackCue) {
			emitFeedback(cue)
		}
	}
}
