package com.adsamcik.tracker.game.minigame.territory

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
import com.adsamcik.tracker.game.minigame.TerritoryConfiguration
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import javax.inject.Inject

/**
 * Claim new 50m grid cells during a session. Score = unique cells.
 *
 * Cells are ~50m on a side and the session debounces internally at 2 s, so
 * BALANCED power at 2 s cadence with a 5 m displacement filter is plenty —
 * tighter fixes would not unlock any extra cells.
 */
internal class TerritoryGame @Inject constructor() : MiniGame {
	override val id: String = "territory"
	override val nameRes: Int = R.string.minigame_territory_name
	override val descriptionRes: Int = R.string.minigame_territory_description
	override val unlockLevel: Int = 6
	override val defaultConfiguration: TerritoryConfiguration = MiniGameConfigurations.DEFAULT_TERRITORY
	override val supportedConfigurations: List<TerritoryConfiguration> = MiniGameConfigurations.TERRITORY
	override val scoreUnit: MiniGameScoreUnit = MiniGameScoreUnit.CELL_COUNT
	override val presentation: MiniGamePresentation = MiniGamePresentation(
		icon = MiniGameIcon.GRID_FLAG,
		accentRole = MiniGameAccentRole.PRIMARY,
		shapeRole = MiniGameShapeRole.TERRAIN,
	)

	override fun createSession(): MiniGameSession = TerritorySession(defaultConfiguration)

	override fun createSession(configuration: MiniGameConfiguration): MiniGameSession {
		require(configuration is TerritoryConfiguration && configuration in supportedConfigurations) {
			"Unsupported configuration for mini-game '$id'"
		}
		return TerritorySession(configuration)
	}

	override fun desiredLocationRequest(): LocationRequest =
		LocationRequest.Builder(INTERVAL_MS)
			.setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
			.setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
			.setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
			.build()

	private companion object {
		private const val INTERVAL_MS = 2_000L
		private const val MIN_INTERVAL_MS = 1_500L
		private const val MIN_DISPLACEMENT_M = 5f
	}
}
