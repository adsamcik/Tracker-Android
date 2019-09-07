package com.adsamcik.tracker.tracker.notification

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference

interface BaseTrackerNotificationComponent {
	/**
	 * Id used to get proper preference
	 */
	val id: String

	/**
	 * Title resource
	 */
	val titleRes: Int

	/**
	 * Generates notification text
	 */
	fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String?
}

internal abstract class TrackerNotificationComponent : BaseTrackerNotificationComponent {
	override val id: String
		get() = defaultPreference.id

	abstract val defaultPreference: NotificationPreference
	var preference: NotificationPreference = NotificationPreference.EMPTY
}
