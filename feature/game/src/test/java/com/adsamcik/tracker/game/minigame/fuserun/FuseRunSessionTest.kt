package com.adsamcik.tracker.game.minigame.fuserun

import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.FuseRunGoal
import com.adsamcik.tracker.game.minigame.FuseRunVisualPayload
import com.adsamcik.tracker.game.minigame.MiniGameDifficulty
import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGamePhase
import com.adsamcik.tracker.game.minigame.MiniGameSignalQuality
import com.adsamcik.tracker.game.minigame.MiniGameState
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuseRunSessionTest {

	@Test
	fun `idle start exposes configured goal without participation`() {
		val session = FuseRunSession(
			configuration = FuseRunConfiguration(FuseRunGoal.CHARGES_3),
		)

		val progress = session.snapshot.goalProgress as MiniGameGoalProgress.Tracked
		val payload = session.visualPayload

		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(MiniGamePhase.WAITING_TO_START, session.snapshot.phase)
		assertEquals(0.0, progress.current)
		assertEquals(3.0, progress.target)
		assertFalse(payload.hasStarted)
		assertEquals(0, session.calculatePoints())
	}

	@Test
	fun `first accepted fix arms the first charge`() {
		val session = FuseRunSession()
		session.onActiveElapsedTimeChanged(0L)

		session.onLocationUpdate(BASE_LATITUDE, BASE_LONGITUDE, 1f, 5f, 10_000L)

		val payload = session.visualPayload
		assertEquals(MiniGameState.RUNNING, session.state)
		assertEquals(MiniGamePhase.ACTIVE, session.snapshot.phase)
		assertTrue(payload.hasStarted)
		assertEquals(0.0, payload.distanceFromChargeMeters)
		assertEquals(payload.roundDurationMs, payload.remainingTimeMs)
	}

	@Test
	fun `snapshot exposes the typed fuse run payload`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)

		driver.start()

		assertTrue(session.snapshot.visualPayload is FuseRunVisualPayload)
		assertFalse(session.snapshot.visualPayload is MiniGameVisualPayload.Pending)
	}

	@Test
	fun `low accuracy fix is rejected before the game starts`() {
		val session = FuseRunSession()

		session.onLocationUpdate(BASE_LATITUDE, BASE_LONGITUDE, 1f, 45f, 0L)

		val payload = session.visualPayload
		assertEquals(MiniGameState.IDLE, session.state)
		assertFalse(payload.hasStarted)
		assertEquals(MiniGameSignalQuality.POOR, session.snapshot.signal.quality)
	}

	@Test
	fun `mid game low accuracy fix cannot change distance or score`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()
		driver.move(distanceMeters = 10.0)
		val acceptedDistance = session.visualPayload.distanceFromChargeMeters

		driver.move(distanceMeters = 100.0, accuracyM = 45f)

		assertEquals(0.0, session.score)
		assertEquals(acceptedDistance, session.visualPayload.distanceFromChargeMeters, 0.001)
		assertEquals(MiniGameSignalQuality.POOR, session.snapshot.signal.quality)
	}

	@Test
	fun `vehicle speed rearms without granting distance or a defusal`() {
		val session = FuseRunSession(
			configuration = FuseRunConfiguration(FuseRunGoal.CHARGES_3),
		)
		val driver = FuseRunDriver(session)
		driver.start()

		driver.move(distanceMeters = 120.0, speedMps = 12f)

		val afterVehicle = session.visualPayload
		assertEquals(0.0, session.score)
		assertEquals(0.0, afterVehicle.distanceFromChargeMeters)

		driver.move(distanceMeters = 5.0, speedMps = 1.5f)
		val afterWalking = session.visualPayload
		assertTrue(afterWalking.distanceFromChargeMeters in 4.0..6.0)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `defusing configured number of charges completes the goal`() {
		val session = FuseRunSession(
			configuration = FuseRunConfiguration(
				goal = FuseRunGoal.CHARGES_3,
				difficulty = MiniGameDifficulty.NORMAL,
			),
		)
		val driver = FuseRunDriver(session)
		driver.start()

		repeat(3) { driver.defuseCurrentCharge() }

		val progress = session.snapshot.goalProgress as MiniGameGoalProgress.Tracked
		assertEquals(3.0, session.score)
		assertTrue(progress.isReached)
		assertEquals(MiniGameState.FINISHED, session.state)
		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.GoalReached)
	}

	@Test
	fun `critical fuse feedback fires once on the warning transition`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()

		driver.advanceTimeBy(22_000L)
		val critical = requireNotNull(session.snapshot.latestFeedback)
		assertTrue(critical.cue is MiniGameFeedbackCue.FuseCritical)

		driver.advanceTimeBy(1_000L)

		assertEquals(critical.eventId, session.snapshot.latestFeedback?.eventId)
	}

	@Test
	fun `defusal feedback fires for ordinary charges but goal wins on the final charge`() {
		val session = FuseRunSession(
			configuration = FuseRunConfiguration(FuseRunGoal.CHARGES_3),
		)
		val driver = FuseRunDriver(session)
		driver.start()

		driver.defuseCurrentCharge()
		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.FuseDefused)
		driver.defuseCurrentCharge()
		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.FuseDefused)

		driver.defuseCurrentCharge()

		assertTrue(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.GoalReached)
	}

	@Test
	fun `personal best feedback crosses once and remains edge triggered`() {
		val session = FuseRunSession(
			configuration = FuseRunConfiguration(FuseRunGoal.CHARGES_8),
			personalBestBeforeRun = 1.0,
		)
		val driver = FuseRunDriver(session)
		driver.start()

		driver.defuseCurrentCharge()
		assertFalse(session.snapshot.latestFeedback?.cue is MiniGameFeedbackCue.PersonalBestCrossed)

		driver.defuseCurrentCharge()
		val crossed = session.snapshot.latestFeedback
		assertNotNull(crossed)
		assertTrue(crossed?.cue is MiniGameFeedbackCue.PersonalBestCrossed)

		driver.defuseCurrentCharge()
		val nextFeedback = requireNotNull(session.snapshot.latestFeedback)
		assertTrue(nextFeedback.cue is MiniGameFeedbackCue.FuseDefused)
		assertTrue(nextFeedback.eventId.value > requireNotNull(crossed).eventId.value)
	}

	@Test
	fun `points guard requires a real successful escape`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()
		driver.move(distanceMeters = 10.0)
		session.onSessionEnd()

		assertEquals(0, session.calculatePoints())
	}

	@Test
	fun `repeated fuse expiries never create score or points`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()

		repeat(3) { driver.advanceTimeBy(30_000L) }
		session.onSessionEnd()

		assertEquals(0.0, session.score)
		assertEquals(0, session.calculatePoints())
		assertEquals(4, session.visualPayload.roundNumber)
	}

	@Test
	fun `fuse expiry resets current streak while preserving best streak`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()
		repeat(2) { driver.defuseCurrentCharge() }
		assertEquals(2, session.visualPayload.currentStreak)

		driver.advanceTimeBy(session.visualPayload.remainingTimeMs)

		assertEquals(0, session.visualPayload.currentStreak)
		assertEquals(2, session.visualPayload.bestStreak)
	}

	@Test
	fun `points are balanced across short default and maximum goals`() {
		val shortSession = FuseRunSession(
			configuration = FuseRunConfiguration(
				goal = FuseRunGoal.CHARGES_3,
				difficulty = MiniGameDifficulty.EASY,
			),
		)
		val shortDriver = FuseRunDriver(shortSession)
		shortDriver.start()
		repeat(3) { shortDriver.defuseCurrentCharge() }

		val defaultSession = FuseRunSession()
		val defaultDriver = FuseRunDriver(defaultSession)
		defaultDriver.start()
		repeat(5) { defaultDriver.defuseCurrentCharge() }

		val maximumSession = FuseRunSession(
			configuration = FuseRunConfiguration(
				goal = FuseRunGoal.CHARGES_8,
				difficulty = MiniGameDifficulty.EASY,
			),
		)
		val maximumDriver = FuseRunDriver(maximumSession)
		maximumDriver.start()
		repeat(8) { maximumDriver.defuseCurrentCharge() }

		assertEquals(81, shortSession.calculatePoints())
		assertEquals(115, defaultSession.calculatePoints())
		assertEquals(180, maximumSession.calculatePoints())
	}

	@Test
	fun `active elapsed time drives warning and expiry with the screen off`() {
		val session = FuseRunSession()
		val driver = FuseRunDriver(session)
		driver.start()
		val initialRound = session.visualPayload.roundNumber

		session.onActiveElapsedTimeChanged(23_000L)
		assertEquals(MiniGameState.WARNING, session.state)

		session.onActiveElapsedTimeChanged(30_000L)
		val rearmed = session.visualPayload
		assertEquals(MiniGameState.RUNNING, session.state)
		assertEquals(initialRound + 1, rearmed.roundNumber)
		assertEquals(0.0, rearmed.distanceFromChargeMeters)
	}

	@Test
	fun `first runtime elapsed callback safely resets a wall clock fuse`() {
		val session = FuseRunSession()
		session.onLocationUpdate(BASE_LATITUDE, BASE_LONGITUDE, 1f, 5f, 1_000_000L)

		session.onActiveElapsedTimeChanged(5_000L)

		assertEquals(
			session.visualPayload.roundDurationMs,
			session.visualPayload.remainingTimeMs,
		)
		assertEquals(MiniGameState.RUNNING, session.state)
	}

	private class FuseRunDriver(
		private val session: FuseRunSession,
	) {
		private var latitude: Double = BASE_LATITUDE
		private var longitude: Double = BASE_LONGITUDE
		private var activeTimeMs: Long = 0L

		fun start() {
			session.onActiveElapsedTimeChanged(activeTimeMs)
			session.onLocationUpdate(latitude, longitude, 1f, 5f, activeTimeMs)
		}

		fun move(
			distanceMeters: Double,
			speedMps: Float = 1.5f,
			accuracyM: Float = 5f,
		) {
			activeTimeMs += 1_000L
			latitude += distanceMeters / METERS_PER_LATITUDE_DEGREE
			session.onActiveElapsedTimeChanged(activeTimeMs)
			session.onLocationUpdate(latitude, longitude, speedMps, accuracyM, activeTimeMs)
		}

		fun defuseCurrentCharge() {
			val payload = session.visualPayload
			move(payload.requiredDistanceMeters + 3.0)
		}

		fun advanceTimeBy(durationMs: Long) {
			activeTimeMs += durationMs
			session.onActiveElapsedTimeChanged(activeTimeMs)
		}
	}

	private companion object {
		const val BASE_LATITUDE: Double = 51.0
		const val BASE_LONGITUDE: Double = 14.0
		const val METERS_PER_LATITUDE_DEGREE: Double = 111_111.0
	}
}
