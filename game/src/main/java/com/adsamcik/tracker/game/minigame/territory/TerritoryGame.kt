package com.adsamcik.tracker.game.minigame.territory

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameSession
import javax.inject.Inject

/** Claim new 50m grid cells during a session. Score = unique cells. */
class TerritoryGame @Inject constructor() : MiniGame {
	override val id: String = "territory"
	override val nameRes: Int = R.string.minigame_territory_name
	override val descriptionRes: Int = R.string.minigame_territory_description
	override val unlockLevel: Int = 6

	override fun createSession(): MiniGameSession = TerritorySession()
}
