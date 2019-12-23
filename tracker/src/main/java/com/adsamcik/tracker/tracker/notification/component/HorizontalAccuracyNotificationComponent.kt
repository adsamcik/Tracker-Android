package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class HorizontalAccuracyNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.horizontal_accuracy_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val location = data.location ?: return null
		val altitude = location.horizontalAccuracy ?: return null

		return context.getString(
				R.string.horizontal_accuracy_value,
				context.resources.formatDistance(altitude, 0, com.adsamcik.tracker.common.preferences.Preferences.getLengthSystem(context))
		)
	}
}
