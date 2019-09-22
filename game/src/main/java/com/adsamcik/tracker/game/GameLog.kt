package com.adsamcik.tracker.game

import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.debug.Logger

internal fun logGame(data: LogData) = Logger.logWithPreference(
		data,
		R.string.settings_log_games_key,
		R.string.settings_log_games_default
)
