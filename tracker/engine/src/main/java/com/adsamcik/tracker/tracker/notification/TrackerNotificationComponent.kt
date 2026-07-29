package com.adsamcik.tracker.tracker.notification

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

internal abstract class TrackerNotificationComponent {
	/**
	 * Stable ID used by the legacy Room preference table.
	 */
	val id: String
		get() = defaultPreference.id

	abstract val titleRes: Int

	abstract val defaultPreference: NotificationPreference

	var preference: NotificationPreference = NotificationPreference.EMPTY

	/**
	 * Generates notification text
	 */
	abstract fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String?
}
