package com.adsamcik.tracker.game.goals

import android.text.InputType
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.findViewTreeLifecycleOwner
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

/**
 * Creates settings for goals.
 */
class GoalsSettings : SubmoduleSettings {
	override val categoryTitleRes: Int = R.string.settings_game_goals_category_title

	@Composable
	private fun NumberInputDialog(
		title: String,
		currentValue: String,
		onDismiss: () -> Unit,
		onConfirm: (String) -> Unit
	) {
		var inputValue by remember { mutableStateOf(currentValue) }

		AlertDialog(
			onDismissRequest = onDismiss,
			title = { Text(title) },
			text = {
				Column {
					OutlinedTextField(
						value = inputValue,
						onValueChange = { inputValue = it },
						label = { Text(title) },
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						modifier = Modifier.fillMaxWidth()
					)
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						onConfirm(inputValue)
						onDismiss()
					}
				) {
					Text(stringResource(com.adsamcik.tracker.shared.base.R.string.generic_done))
				}
			},
			dismissButton = {
				TextButton(onClick = onDismiss) {
					Text(stringResource(android.R.string.cancel))
				}
			}
		)
	}

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
			val composeView = ComposeView(preference.context).apply {
				setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(findViewTreeLifecycleOwner()!!))
				var showDialog by mutableStateOf(true)
				
				setContent {
					if (showDialog) {
						NumberInputDialog(
							title = title.toString(),
							currentValue = preferences.getInt(key, default).toString(),
							onDismiss = { showDialog = false },
							onConfirm = { inputValue ->
								val number = inputValue.toIntOrNull() ?: default
								Preferences.getPref(preference.context)
									.edit { setInt(key, number) }
								summary = number.formatReadable()
							}
						)
					}
				}
			}
			
			// Add to view hierarchy temporarily
			val parentViewGroup = preference.parent as? android.view.ViewGroup
			parentViewGroup?.addView(composeView)
			
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
