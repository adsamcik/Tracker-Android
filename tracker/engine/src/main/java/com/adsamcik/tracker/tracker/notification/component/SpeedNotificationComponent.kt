package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.tracker.controller.forLiveSegment
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.shared.preferences.extension.formatSpeed
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent

internal class SpeedNotificationComponent : TrackerNotificationComponent() {
	override val source: SourceKind get() = SourceKind.LOCATION
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = true
		)

	override val titleRes: Int
		get() = R.string.speed_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead
	): String? {
		val speed = (savedLocation.forLiveSegment(session.id, true) as? SavedLocationSummaryRead.Snapshot)
            ?.summary?.latestFix?.speedMetersPerSecond ?: return null
		return context.getString(
				R.string.speed_value,
				context.resources.formatSpeed(context, speed.toDouble(), 1)
		)
	}
}
