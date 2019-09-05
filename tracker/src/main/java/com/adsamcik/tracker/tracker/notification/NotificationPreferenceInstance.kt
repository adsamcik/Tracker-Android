package com.adsamcik.tracker.tracker.notification

import com.adsamcik.tracker.common.database.data.NotificationPreference

data class NotificationPreferenceInstance(
		val preference: NotificationPreference,
		val titleRes: Int
)
