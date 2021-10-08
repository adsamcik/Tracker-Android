package com.adsamcik.tracker.activity

import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger

internal fun logActivity(data: LogData) = Logger.logWithPreference(
		data,
		R.string.settings_log_activity_key,
		R.string.settings_log_activity_default
)

internal const val ACTIVITY_LOG_SOURCE = "activity"
