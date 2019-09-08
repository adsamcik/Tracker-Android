package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class CellCountNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = true,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.cell_count_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val cell = data.cell ?: return null
		return context.getString(R.string.cell_count_value, cell.totalCount)
	}
}
