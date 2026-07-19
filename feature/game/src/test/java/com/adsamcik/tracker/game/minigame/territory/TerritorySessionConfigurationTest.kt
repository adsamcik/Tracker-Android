package com.adsamcik.tracker.game.minigame.territory

import com.adsamcik.tracker.game.minigame.MiniGameFeedbackCue
import com.adsamcik.tracker.game.minigame.MiniGameGoalProgress
import com.adsamcik.tracker.game.minigame.MiniGameVisualPayload
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.adsamcik.tracker.game.minigame.TerritoryGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerritorySessionConfigurationTest {

	@Test
	fun `first accepted fix creates private origin and snapshot contains only local grid cells`() {
		val session = TerritorySession()

		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		session.onLocationUpdate(51.0, 14.01, 1f, 5f, 2_000L)

		val payload = assertType<MiniGameVisualPayload.Territory>(session.snapshot.visualPayload)
		assertEquals(1, payload.claimedCells.size)
		assertNull(payload.currentCell)
		assertTrue(payload.recentTrail.all { it.rowOffset in -3..3 && it.columnOffset in -3..3 })
		assertEquals(2.0, session.score)
	}

	@Test
	fun `selected goal and new cell feedback are edge triggered`() {
		val session = TerritorySession(
			configuration = TerritoryConfiguration(TerritoryGoal.CELLS_5),
		)

		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 0L)
		val firstEventId = session.snapshot.latestFeedback!!.eventId
		session.onLocationUpdate(51.0, 14.0, 1f, 5f, 2_000L)

		assertEquals(firstEventId, session.snapshot.latestFeedback!!.eventId)
		assertEquals(1.0, session.score)
		assertEquals(5.0, (session.snapshot.goalProgress as MiniGameGoalProgress.Tracked).target)
		assertType<MiniGameFeedbackCue.TerritoryCellClaimed>(session.snapshot.latestFeedback!!.cue)
	}

	@Test
	fun `goal completion publishes goal event`() {
		val session = TerritorySession(
			configuration = TerritoryConfiguration(TerritoryGoal.CELLS_5),
		)

		repeat(5) { index ->
			session.onLocationUpdate(51.0 + index * 0.001, 14.0, 1f, 5f, index * 2_000L)
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
