package com.adsamcik.tracker.activity

import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.preferences.PreferenceKeys

internal fun logActivity(data: LogData) = Logger.logWithStringPreference(
		data,
		PreferenceKeys.LOG_ACTIVITY,
		PreferenceKeys.LOG_ACTIVITY_DEFAULT
)

internal const val ACTIVITY_LOG_SOURCE = "activity"
