package com.adsamcik.tracker.game.challenge

import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.shared.preferences.SubmoduleSettings

/**
 * Creates settings for challenges.
 */
class ChallengeSettings : SubmoduleSettings {
	override val categoryTitleRes: Int = R.string.settings_game_challenge_category_title

	override fun onCreatePreferenceCategory(preferenceCategory: PreferenceCategory) {
		val context = preferenceCategory.context

		val challengeEnablePreference = SwitchPreferenceCompat(context).apply {
			val default = context.resources.getString(R.string.settings_game_challenge_enable_default)
					.toBoolean()
			setDefaultValue(default)
			key = context.getString(R.string.settings_game_challenge_enable_key)
			setTitle(R.string.settings_game_challenge_enable_title)
			setIcon(R.drawable.ic_challenge_icon)
		}

		preferenceCategory.addPreference(challengeEnablePreference)
	}
}
