package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.formatDistance

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class AltitudeNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.altitude_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val location = data.location ?: return null
		val altitude = location.altitude ?: return null

		return context.getString(
				R.string.altitude_value,
				context.resources.formatDistance(altitude, 2, Preferences.getLengthSystem(context))
		)
	}
}
