package com.adsamcik.tracker.game.preference

import androidx.preference.PreferenceScreen
import com.adsamcik.tracker.game.challenge.ChallengeSettings
import com.adsamcik.tracker.game.goals.GoalsSettings
import com.adsamcik.tracker.shared.preferences.ModuleSettings

/**
 * Game module settings
 */
@Suppress("unused")
class GameSettings : ModuleSettings {
	override val iconRes: Int = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_games_24dp

	override fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen) {
		createSubmodule(preferenceScreen, ChallengeSettings())
		createSubmodule(preferenceScreen, GoalsSettings())
	}

}

