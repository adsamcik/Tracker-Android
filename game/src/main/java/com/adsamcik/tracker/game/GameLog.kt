package com.adsamcik.tracker.game

import com.adsamcik.tracker.activity.R

internal fun logGame(data: com.adsamcik.tracker.logger.LogData) =
		com.adsamcik.tracker.logger.Logger.logWithPreference(
				data,
				R.string.settings_log_games_key,
				R.string.settings_log_games_default
		)

internal const val GAME_LOG_SOURCE = "game"
internal const val CHALLENGE_LOG_SOURCE = "game_challenge"
internal const val GOALS_LOG_SOURCE = "game_goals"
