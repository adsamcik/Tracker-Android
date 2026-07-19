package com.adsamcik.tracker.game.session

import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton application-scoped control surface for foreground game sessions.
 */
internal interface GameSessionController {
	val state: StateFlow<GameSessionState>

	fun start(configuration: MiniGameConfiguration)

	fun pause()

	fun resume()

	fun finish()
}
