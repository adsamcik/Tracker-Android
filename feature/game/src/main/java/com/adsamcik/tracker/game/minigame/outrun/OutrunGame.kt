package com.adsamcik.tracker.game.minigame.outrun

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
import com.adsamcik.tracker.game.minigame.OutrunConfiguration
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Ghost race: a virtual ghost runs at a constant pace. Stay ahead.
 * Score = max distance ahead of ghost.
 *
 * Requests HIGH_ACCURACY fixes at ~1 s so the gap-to-ghost number on screen
 * updates smoothly; sub-second cadence would burn battery without changing
 * what the player perceives.
 */
internal class OutrunGame @Inject constructor() : MiniGame {
	override val id: String = "outrun"
	override val nameRes: Int = R.string.minigame_outrun_name
	override val descriptionRes: Int = R.string.minigame_outrun_description
	override val unlockLevel: Int = 3
	override val defaultConfiguration: OutrunConfiguration = MiniGameConfigurations.DEFAULT_OUTRUN
	override val supportedConfigurations: List<OutrunConfiguration> = MiniGameConfigurations.OUTRUN
	override val scoreUnit: MiniGameScoreUnit = MiniGameScoreUnit.DISTANCE_METERS
	override val presentation: MiniGamePresentation = MiniGamePresentation(
		icon = MiniGameIcon.GHOST,
		accentRole = MiniGameAccentRole.TERTIARY,
		shapeRole = MiniGameShapeRole.MOMENTUM,
	)

	override fun createSession(): MiniGameSession = OutrunSession(defaultConfiguration)

	override fun createSession(configuration: MiniGameConfiguration): MiniGameSession {
		require(configuration is OutrunConfiguration && configuration in supportedConfigurations) {
			"Unsupported configuration for mini-game '$id'"
		}
		return OutrunSession(configuration)
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
