package com.adsamcik.tracker.game.minigame

import com.adsamcik.tracker.game.minigame.outrun.OutrunSession
import com.adsamcik.tracker.game.minigame.territory.TerritorySession
import com.adsamcik.tracker.game.minigame.zenwalk.ZenWalkSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MiniGameSessionTest {

	// --- Outrun ---
	@Test
	fun `outrun starts idle`() {
		val session = OutrunSession()
		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `outrun transitions to running on first fix`() {
		val session = OutrunSession()
		session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
		assertEquals(MiniGameState.RUNNING, session.state)
	}

	@Test
	fun `outrun ignores low accuracy`() {
		val session = OutrunSession()
		session.onLocationUpdate(51.0, 14.0, 1.5f, 50f, 1000L)
		assertEquals(MiniGameState.IDLE, session.state)
	}

	@Test
	fun `outrun awards no points before the race starts`() {
		val session = OutrunSession()
		assertEquals(0, session.calculatePoints())
	}

	// --- Territory ---
	@Test
	fun `territory starts idle`() {
		val session = TerritorySession()
		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `territory claims cell on valid update`() {
		val session = TerritorySession()
		session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
		assertEquals(1.0, session.score)
	}

	@Test
	fun `territory rejects vehicle speed`() {
		val session = TerritorySession()
		session.onLocationUpdate(51.0, 14.0, 12f, 10f, 1000L)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `territory debounces updates`() {
		val session = TerritorySession()
		session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
		session.onLocationUpdate(51.001, 14.001, 1.5f, 10f, 2000L) // within 2s
		assertEquals(1.0, session.score) // debounced
	}

	@Test
	fun `territory awards no points without meaningful exploration`() {
		val session = TerritorySession()
		session.onLocationUpdate(51.0, 14.0, 1.5f, 10f, 1000L)
		assertEquals(0, session.calculatePoints())
	}

	// --- Zen Walk ---
	@Test
	fun `zen walk starts idle`() {
		val session = ZenWalkSession()
		assertEquals(MiniGameState.IDLE, session.state)
		assertEquals(0.0, session.score)
	}

	@Test
	fun `zen walk with fixed target counts in-zone time`() {
		val session = ZenWalkSession(fixedTargetMps = 1.4) // ~5 km/h
		session.onLocationUpdate(51.0, 14.0, 1.4f, 10f, 0L) // first fix
		// Simulate 10 seconds of walking at exactly target pace
		for (i in 1..10) {
			session.onLocationUpdate(51.0 + i * 0.00001, 14.0, 1.4f, 10f, i * 1000L)
		}
		assertTrue(session.score > 0)
	}

	@Test
	fun `zen walk awards no points without time in zone`() {
		val session = ZenWalkSession()
		assertEquals(0, session.calculatePoints())
	}
}
