package com.adsamcik.tracker.game.minigame.outrun

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameSession
import javax.inject.Inject

/**
 * Ghost race: a virtual ghost runs at a constant pace. Stay ahead.
 * Score = max distance ahead of ghost.
 */
class OutrunGame @Inject constructor() : MiniGame {
	override val id: String = "outrun"
	override val nameRes: Int = R.string.minigame_outrun_name
	override val descriptionRes: Int = R.string.minigame_outrun_description
	override val unlockLevel: Int = 3

	override fun createSession(): MiniGameSession = OutrunSession()
}
