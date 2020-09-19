package com.adsamcik.tracker.activity

import com.adsamcik.tracker.shared.utils.debug.LogData
import com.adsamcik.tracker.shared.utils.debug.Logger

internal fun logActivity(data: LogData) = Logger.logWithPreference(
		data,
		R.string.settings_log_activity_key,
		R.string.settings_log_activity_default
)
