package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.tracker.controller.formatSavedDistance
import com.adsamcik.tracker.tracker.controller.forLiveSegment
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.formatDistance

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class DistanceNotificationComponent : TrackerNotificationComponent() {
	override val source: SourceKind get() = SourceKind.LOCATION
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = true,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.distance_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead
	): String {
        val read = savedLocation.forLiveSegment(session.id, true)
        return context.getString(R.string.distance_value, read.formatSavedDistance(context) {
            context.resources.formatDistance(it, 0, TrackerSettingsQuick.lengthSystem(context))
        })
    }
}
