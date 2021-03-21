package com.adsamcik.tracker.game.goals

import android.text.InputType
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.preference.sliders.FloatSliderPreference
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.format
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.SubmoduleSettings
import com.adsamcik.tracker.shared.utils.extension.dynamicStyle
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input

/**
 * Creates settings for goals.
 */
class GoalsSettings : SubmoduleSettings {
	override val categoryTitleRes: Int = R.string.settings_game_goals_category_title

	private fun Preference.initializeEditNumberDialogPreference(
			preferences: Preferences,
			keyRes: Int,
			defaultRes: Int
	) {
		val default = context
				.getString(defaultRes)
				.toInt()
		val key = context.getString(keyRes)
		this.key = key
		summary = preferences
				.getInt(key, default)
				.formatReadable()
		setOnPreferenceClickListener { preference ->
			MaterialDialog(preference.context)
					.show {
						positiveButton(com.adsamcik.tracker.shared.base.R.string.generic_done)
						input(
								hint = title.toString(),
								prefill = preferences.getInt(key, default).toString(),
								inputType = InputType.TYPE_CLASS_NUMBER,
								waitForPositiveButton = true,
								callback = { dialog, input ->
									val number = input.toString().toInt()
									Preferences.getPref(dialog.windowContext)
											.edit { setInt(key, number) }
									summary = number.formatReadable()
								})
						dynamicStyle()
					}
			false
		}
	}

	override fun onCreatePreferenceCategory(preferenceCategory: PreferenceCategory) {
		val context = preferenceCategory.context
		val preferences = Preferences.getPref(context)

		val notificationEnablePreference = SwitchPreferenceCompat(context).apply {
			val default = context
					.resources
					.getString(R.string.settings_game_goals_notification_enabled_default)
					.toBoolean()
			setDefaultValue(default)
			key = context.getString(R.string.settings_game_goals_notification_enabled_key)
			setTitle(R.string.settings_game_goals_notification_enabled_title)
			setIcon(R.drawable.ic_bell_icon)
		}

		val dailyStepPreference = Preference(context).apply {
			setTitle(R.string.settings_game_goals_day_steps_title)
			setIcon(R.drawable.ic_day)

			initializeEditNumberDialogPreference(
					preferences,
					R.string.settings_game_goals_day_steps_key,
					R.string.settings_game_goals_day_steps_default
			)
		}

		val weeklyStepPreference = Preference(context).apply {
			setTitle(R.string.settings_game_goals_week_steps_title)
			setIcon(R.drawable.ic_week)

			initializeEditNumberDialogPreference(
					preferences,
					R.string.settings_game_goals_week_steps_key,
					R.string.settings_game_goals_week_steps_default
			)
		}

		val weeklyStepDailyPercentagePreference = FloatSliderPreference(context).apply {
			setTitle(R.string.settings_game_goals_week_steps_daily_percentage_title)
			setSummary(R.string.settings_game_goals_week_steps_daily_percentage_summary)
			key = context.getString(R.string.settings_game_goals_week_steps_daily_percentage_key)
			initialValue = context.getString(R.string.settings_game_goals_week_steps_daily_percentage_default)
					.toFloat()
			minValue = 1f / Time.WEEK_IN_DAYS
			maxValue = 1f
			step = 1f / (Time.WEEK_IN_DAYS * Time.QUARTER_DAY_IN_HOURS)
			val formatString = context.getString(com.adsamcik.tracker.shared.utils.R.string.percentage_format)
			labelFormatter = { formatString.format((it * 100f).format(0)) }
		}

		preferenceCategory.addPreference(notificationEnablePreference)
		preferenceCategory.addPreference(dailyStepPreference)
		preferenceCategory.addPreference(weeklyStepPreference)
		preferenceCategory.addPreference(weeklyStepDailyPercentagePreference)
	}
}
