package com.adsamcik.tracker.game.minigame.fuserun

import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.minigame.FuseRunConfiguration
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameAccentRole
import com.adsamcik.tracker.game.minigame.MiniGameConfiguration
import com.adsamcik.tracker.game.minigame.MiniGameConfigurations
import com.adsamcik.tracker.game.minigame.MiniGameIcon
import com.adsamcik.tracker.game.minigame.MiniGamePresentation
import com.adsamcik.tracker.game.minigame.MiniGameScoreUnit
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.minigame.MiniGameShapeRole
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Escape each virtual charge before its fuse expires. Score = charges defused.
 *
 * Fuse Run needs a responsive, accurate distance feed because its shortest
 * escape radius is 18 m and the warning state is time-sensitive.
 */
internal class FuseRunGame @Inject constructor() : MiniGame {
	override val id: String = FuseRunConfiguration.GAME_ID
	override val nameRes: Int = R.string.minigame_fuserun_name
	override val descriptionRes: Int = R.string.minigame_fuserun_description
	override val unlockLevel: Int = 9
	override val defaultConfiguration: FuseRunConfiguration =
		MiniGameConfigurations.DEFAULT_FUSE_RUN
	override val supportedConfigurations: List<FuseRunConfiguration> =
		MiniGameConfigurations.FUSE_RUN
	override val scoreUnit: MiniGameScoreUnit = MiniGameScoreUnit.DEFUSAL_COUNT
	override val presentation: MiniGamePresentation = MiniGamePresentation(
		icon = MiniGameIcon.FUSE,
		accentRole = MiniGameAccentRole.TERTIARY,
		shapeRole = MiniGameShapeRole.MOMENTUM,
	)

	override fun createSession(): MiniGameSession = FuseRunSession(defaultConfiguration)

	override fun createSession(configuration: MiniGameConfiguration): MiniGameSession {
		require(configuration is FuseRunConfiguration && configuration in supportedConfigurations) {
			"Unsupported configuration for mini-game '$id'"
		}
		return FuseRunSession(configuration)
	}

	override fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(INTERVAL_MS)
			.setPriority(Priority.PRIORITY_HIGH_ACCURACY)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(0f)
			.build()

	private companion object {
		private const val INTERVAL_MS = 1_000L
		private const val MIN_INTERVAL_MS = 500L
	}
}
