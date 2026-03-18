package com.adsamcik.tracker.game

import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.PreferenceKeys

internal fun logGame(data: LogData) =
		Logger.logWithStringPreference(
				data,
				PreferenceKeys.LOG_GAMES,
				PreferenceKeys.LOG_GAMES_DEFAULT
		)

internal const val GAME_LOG_SOURCE = "game"
internal const val CHALLENGE_LOG_SOURCE = "game_challenge"
internal const val GOALS_LOG_SOURCE = "game_goals"
