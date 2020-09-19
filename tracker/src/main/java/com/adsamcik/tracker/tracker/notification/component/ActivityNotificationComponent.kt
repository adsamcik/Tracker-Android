package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import java.util.*

internal class ActivityNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.activity_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val activity = data.activity ?: return null
		return context.getString(
				R.string.activity_value,
				context.getString(activity.activity.nameRes).toLowerCase(Locale.getDefault())
		)
	}
}
