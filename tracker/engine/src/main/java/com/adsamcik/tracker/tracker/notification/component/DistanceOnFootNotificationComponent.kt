package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class DistanceOnFootNotificationComponent : TrackerNotificationComponent() {
	override val source: SourceKind get() = SourceKind.LOCATION
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.distance_onfoot_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead
	): String {
        // The native saved product has no mode-attributed distance; a raw counter is not a measured zero.
        return context.getString(R.string.distance_onfoot_value, context.getString(R.string.tracker_steps_value_unavailable))
    }
}
