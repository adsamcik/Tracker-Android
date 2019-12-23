package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class DistanceNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = true,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.distance_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		return context.getString(
				R.string.distance_value,
				context.resources.formatDistance(
						session.distanceInM,
						0,
						Preferences.getLengthSystem(context)
				)
		)
	}
}
