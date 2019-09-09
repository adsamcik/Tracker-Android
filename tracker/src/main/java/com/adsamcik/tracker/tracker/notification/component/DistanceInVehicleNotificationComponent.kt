package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class DistanceInVehicleNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.distance_invehicle_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		return context.getString(
				R.string.distance_invehicle_value,
				context.resources.formatDistance(
						session.distanceInVehicleInM,
						0,
						Preferences.getLengthSystem(context)
				)
		)
	}
}
