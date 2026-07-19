package com.adsamcik.tracker.game.minigame.switchback

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameAccentRole
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameIcon
import com.adsamcik.tracker.game.minigame.MiniGamePresentation
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameShapeRole
import com.adsamcik.tracker.game.minigame.SwitchbackConfiguration
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Carve sharp route turns and build an alternating flow. Score = turns carved.
 *
 * Turn geometry benefits from high-accuracy fixes, while a 5 m displacement
 * filter avoids needless updates well below the shortest 24 m scoring leg.
 */
internal class SwitchbackGame @Inject constructor() : MiniGame {
	override val id: String = SwitchbackConfiguration.GAME_ID
	override val nameRes: Int = R.string.minigame_switchback_name
	override val descriptionRes: Int = R.string.minigame_switchback_description
	override val unlockLevel: Int = 12
	override val defaultConfiguration: SwitchbackConfiguration =
		MiniGameConfigurations.DEFAULT_SWITCHBACK
	override val supportedConfigurations: List<SwitchbackConfiguration> =
		MiniGameConfigurations.SWITCHBACK
	override val scoreUnit: MiniGameScoreUnit = MiniGameScoreUnit.TURN_COUNT
	override val presentation: MiniGamePresentation = MiniGamePresentation(
		icon = MiniGameIcon.SWITCHBACK,
		accentRole = MiniGameAccentRole.PRIMARY,
		shapeRole = MiniGameShapeRole.TERRAIN,
	)

	override fun createSession(): MiniGameSession = SwitchbackSession(defaultConfiguration)

	override fun createSession(configuration: MiniGameConfiguration): MiniGameSession {
		require(configuration is SwitchbackConfiguration && configuration in supportedConfigurations) {
			"Unsupported configuration for mini-game '$id'"
		}
		return SwitchbackSession(configuration)
	}

	override fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(INTERVAL_MS)
			.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
			.build()

	private companion object {
		private const val INTERVAL_MS = 2_000L
		private const val MIN_INTERVAL_MS = 1_000L
		private const val MIN_DISPLACEMENT_M = 5f
	}
}
