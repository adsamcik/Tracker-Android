package com.adsamcik.tracker.game

internal fun logGame(data: com.adsamcik.tracker.logger.LogData) =
		com.adsamcik.tracker.logger.Logger.logWithPreference(
				data,
				com.adsamcik.tracker.shared.preferences.R.string.settings_log_games_key,
				com.adsamcik.tracker.shared.preferences.R.string.settings_log_games_default
		)

internal const val GAME_LOG_SOURCE = "game"
internal const val CHALLENGE_LOG_SOURCE = "game_challenge"
internal const val GOALS_LOG_SOURCE = "game_goals"
