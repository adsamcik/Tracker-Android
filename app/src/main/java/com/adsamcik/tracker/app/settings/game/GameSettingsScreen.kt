package com.adsamcik.tracker.app.settings.game

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.settings.GameSettingsUiState
import com.adsamcik.tracker.app.settings.GameSettingsViewModel
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.app.settings.components.SettingsNoticeCard
import com.adsamcik.tracker.app.settings.components.SettingsRowDivider
import com.adsamcik.tracker.app.settings.components.SliderSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import java.text.NumberFormat

@Composable
fun GameSettingsScreen(
	viewModel: GameSettingsViewModel = hiltViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	when (val state = uiState) {
		GameSettingsUiState.Loading -> GameSettingsLoadingContent()
		is GameSettingsUiState.Error -> GameSettingsErrorContent()
		is GameSettingsUiState.Content -> GameSettingsContent(
			settings = state.settings,
			onDailyStepGoalChanged = viewModel::setDailyStepGoal,
			onWeeklyStepGoalChanged = viewModel::setWeeklyStepGoal,
			onWeeklyDailyLimitChanged = viewModel::setWeeklyDailyLimit,
			onNotificationsEnabledChanged = viewModel::setNotificationsEnabled,
			onGameHapticsEnabledChanged = viewModel::setGameHapticsEnabled,
			onQuietCoachingEnabledChanged = viewModel::setQuietCoachingEnabled,
			onRememberLastSetupChanged = viewModel::setRememberLastSetup,
		)
	}
}

@Composable
internal fun GameSettingsContent(
	settings: GoalsSettingsState,
	onDailyStepGoalChanged: (Int) -> Unit = {},
	onWeeklyStepGoalChanged: (Int) -> Unit = {},
	onWeeklyDailyLimitChanged: (Float) -> Unit = {},
	onNotificationsEnabledChanged: (Boolean) -> Unit = {},
	onGameHapticsEnabledChanged: (Boolean) -> Unit = {},
	onQuietCoachingEnabledChanged: (Boolean) -> Unit = {},
	onRememberLastSetupChanged: (Boolean) -> Unit = {},
) {
	val stepFormatter = remember { NumberFormat.getIntegerInstance() }
	val percentageFormatter = remember {
		NumberFormat.getPercentInstance().apply {
			maximumFractionDigits = 0
		}
	}
	val stepsUnit = stringResource(R.string.settings_game_steps_unit)
	val dailyGoal = settings.dailyStepGoal.coerceIn(DAILY_GOAL_RANGE.start.toInt(), DAILY_GOAL_RANGE.endInclusive.toInt())
	val weeklyGoal = settings.weeklyStepGoal.coerceIn(WEEKLY_GOAL_RANGE.start.toInt(), WEEKLY_GOAL_RANGE.endInclusive.toInt())
	val dailyLimit = (settings.weeklyProgressDailyLimit * PERCENT_SCALE)
		.coerceIn(DAILY_LIMIT_RANGE.start, DAILY_LIMIT_RANGE.endInclusive)

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item {
			SettingsGroupCard(
				title = stringResource(R.string.settings_game_goals_title),
				icon = Icons.Default.EmojiEvents,
			) {
				SliderSettingsItem(
					title = stringResource(R.string.settings_game_daily_target_title),
					value = dailyGoal.toFloat(),
					valueRange = DAILY_GOAL_RANGE,
					steps = 28,
					valueLabel = { "${stepFormatter.format(it.toInt())} $stepsUnit" },
					onValueChange = { onDailyStepGoalChanged(it.toInt()) },
				)
				SettingsRowDivider()
				SliderSettingsItem(
					title = stringResource(R.string.settings_game_weekly_target_title),
					value = weeklyGoal.toFloat(),
					valueRange = WEEKLY_GOAL_RANGE,
					steps = 18,
					valueLabel = { "${stepFormatter.format(it.toInt())} $stepsUnit" },
					onValueChange = { onWeeklyStepGoalChanged(it.toInt()) },
				)
				SettingsRowDivider()
				SliderSettingsItem(
					title = stringResource(R.string.settings_game_daily_contribution_title),
					value = dailyLimit,
					valueRange = DAILY_LIMIT_RANGE,
					steps = 16,
					valueLabel = { percentageFormatter.format(it / PERCENT_SCALE) },
					onValueChange = { onWeeklyDailyLimitChanged(it / PERCENT_SCALE) },
				)
			}
			Spacer(Modifier.height(16.dp))
		}
		item {
			SettingsGroupCard(
				title = stringResource(R.string.settings_game_notifications_title),
				icon = Icons.Default.Notifications,
			) {
				SwitchSettingsItem(
					title = stringResource(R.string.settings_game_goal_notifications_title),
					subtitle = stringResource(R.string.settings_game_goal_notifications_summary),
					icon = Icons.Default.Notifications,
					checked = settings.notificationsEnabled,
					onCheckedChange = onNotificationsEnabledChanged,
				)
			}
			Spacer(Modifier.height(16.dp))
		}
		item {
			SettingsGroupCard(
				title = stringResource(R.string.settings_game_during_games_title),
				icon = Icons.Default.SportsEsports,
			) {
				SwitchSettingsItem(
					title = stringResource(R.string.settings_game_haptics_title),
					subtitle = stringResource(R.string.settings_game_haptics_summary),
					icon = Icons.Default.Vibration,
					checked = settings.gameHapticsEnabled,
					onCheckedChange = onGameHapticsEnabledChanged,
				)
				SettingsRowDivider()
				SwitchSettingsItem(
					title = stringResource(R.string.settings_game_quiet_coaching_title),
					subtitle = stringResource(R.string.settings_game_quiet_coaching_summary),
					icon = Icons.AutoMirrored.Filled.VolumeOff,
					checked = settings.quietCoachingEnabled,
					onCheckedChange = onQuietCoachingEnabledChanged,
				)
				SettingsRowDivider()
				SwitchSettingsItem(
					title = stringResource(R.string.settings_game_remember_setup_title),
					subtitle = stringResource(R.string.settings_game_remember_setup_summary),
					icon = Icons.Default.Save,
					checked = settings.rememberLastSetup,
					onCheckedChange = onRememberLastSetupChanged,
				)
			}
			Spacer(Modifier.height(16.dp))
		}
		item {
			SettingsNoticeCard(
				title = stringResource(R.string.settings_game_privacy_battery_title),
				text = stringResource(R.string.settings_game_privacy_battery_disclosure),
				icon = Icons.Default.BatterySaver,
			)
		}
	}
}

@Composable
private fun GameSettingsLoadingContent() {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item {
			CircularProgressIndicator()
			Text(
				text = stringResource(R.string.settings_game_loading),
				modifier = Modifier.padding(top = 16.dp),
			)
		}
	}
}

@Composable
private fun GameSettingsErrorContent() {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(16.dp),
	) {
		item {
			Text(
				text = stringResource(R.string.settings_game_load_error),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}
}

private val DAILY_GOAL_RANGE = 1_000f..30_000f
private val WEEKLY_GOAL_RANGE = 7_000f..140_000f
private val DAILY_LIMIT_RANGE = 15f..100f
private const val PERCENT_SCALE = 100f
