package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class LongitudeNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(ID, 0, isInTitle = true, isInContent = true)

	override val titleRes: Int
		get() = R.string.longitude

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val location = data.location ?: return null
		return Assist.coordinateToString(location.longitude)
	}

	companion object {
		const val ID = "Longitude"
	}
}
