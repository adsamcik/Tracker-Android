package com.adsamcik.tracker.tracker.data

import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.stats.api.threshold.ActivityTypeMapping

internal fun RecognizedActivity.toLegacyActivityInfo(): ActivityInfo = ActivityInfo(
	activityType = ActivityTypeMapping.toPlayServicesCode(type),
	confidence = confidence,
)
