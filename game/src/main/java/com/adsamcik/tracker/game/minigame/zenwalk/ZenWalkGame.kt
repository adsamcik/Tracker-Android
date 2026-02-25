package com.adsamcik.tracker.game.minigame.zenwalk

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameSession
import javax.inject.Inject

/** Keep your pace steady in the zone. Score = total seconds in zone. */
class ZenWalkGame @Inject constructor() : MiniGame {
	override val id: String = "zenwalk"
	override val nameRes: Int = R.string.minigame_zenwalk_name
	override val descriptionRes: Int = R.string.minigame_zenwalk_description
	override val unlockLevel: Int = 9

	override fun createSession(): MiniGameSession = ZenWalkSession()
}
