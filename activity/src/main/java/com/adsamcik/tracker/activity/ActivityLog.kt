package com.adsamcik.tracker.activity

import com.adsamcik.tracker.common.debug.LogData
import com.adsamcik.tracker.common.debug.Logger

internal fun logActivity(data: LogData) = Logger.logWithPreference(
		data,
		R.string.settings_log_activity_key,
		R.string.settings_log_activity_default
)
