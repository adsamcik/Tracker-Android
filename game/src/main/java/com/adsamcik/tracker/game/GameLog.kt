package com.adsamcik.tracker.game

import com.adsamcik.tracker.activity.R
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger

internal fun logGame(data: com.adsamcik.tracker.logger.LogData) =
		com.adsamcik.tracker.logger.Logger.logWithPreference(
				data,
				R.string.settings_log_games_key,
				R.string.settings_log_games_default
		)
