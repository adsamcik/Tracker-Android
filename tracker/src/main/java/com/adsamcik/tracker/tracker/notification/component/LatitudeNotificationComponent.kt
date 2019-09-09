package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import kotlin.math.abs

internal class LatitudeNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.latitude_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val location = data.location ?: return null
		val latitude = location.latitude

		val resource = if (latitude >= 0) {
			R.string.latitude_value_north
		} else {
			R.string.latitude_value_south
		}

		return context.getString(
				resource,
				Assist.coordinateToString(abs(latitude))
		)
	}
}
