package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class CellCurrentNotificationComponent : TrackerNotificationComponent() {
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.cell_current_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData
	): String? {
		val cell = data.cell ?: return null

		val formatString = context.getString(R.string.cell_current_single_value)
		val list = cell.registeredCells.joinToString {
			val networkType = context.getString(it.type.nameRes)
			formatString.format(it.networkOperator.name, networkType, it.dbm)
		}
		return context.getString(R.string.cell_current_value, list)
	}
}
