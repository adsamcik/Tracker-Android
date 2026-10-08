package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import java.util.Locale

internal class ActivityNotificationComponent : TrackerNotificationComponent() {
	override val source: SourceKind get() = SourceKind.ACTIVITY
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
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead
	): String? {
		val activity = data.activity ?: return null
		return context.getString(
				R.string.activity_value,
			context.getString(activity.activity.nameRes).lowercase(Locale.getDefault())
		)
	}
}
