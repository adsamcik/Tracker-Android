package com.adsamcik.tracker.game.preference

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.tracker.common.preference.ModuleSettings
import com.adsamcik.tracker.game.R

@Suppress("unused")
class GameSettings : ModuleSettings {
	override val iconRes: Int = com.adsamcik.tracker.common.R.drawable.ic_outline_games_24dp

	override fun onCreatePreferenceScreen(preferenceScreen: PreferenceScreen) {
		val context = preferenceScreen.context

		val challengeEnablePreference = SwitchPreferenceCompat(context).apply {
			val default = context.resources.getString(R.string.settings_game_challenge_enable_default)
					.toBoolean()
			setDefaultValue(default)
			key = context.getString(R.string.settings_game_challenge_enable_key)
			setTitle(R.string.settings_game_challenge_enable_title)
			setIcon(R.drawable.ic_challenge_icon)
		}

		preferenceScreen.addPreference(challengeEnablePreference)
	}

}

