package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class DurationNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = true,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.duration_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val duration = session.end - session.start
		return context.getString(R.string.duration_value, duration.formatAsDuration(context))
	}
}
