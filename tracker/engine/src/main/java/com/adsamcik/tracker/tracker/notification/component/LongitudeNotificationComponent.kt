package com.adsamcik.tracker.tracker.notification.component

import android.content.Context
import com.adsamcik.tracker.tracker.controller.forLiveSegment
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import kotlin.math.abs

internal class LongitudeNotificationComponent : TrackerNotificationComponent() {
	override val source: SourceKind get() = SourceKind.LOCATION
	override val defaultPreference: NotificationPreference
		get() = NotificationPreference(
				this::class.java.simpleName,
				0,
				isInTitle = false,
				isInContent = false
		)

	override val titleRes: Int
		get() = R.string.longitude_title

	override fun generateText(
			context: Context,
			session: TrackerSession,
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead
	): String? {
		val location = (savedLocation.forLiveSegment(session.id, true) as? SavedLocationSummaryRead.Snapshot)
            ?.summary?.latestFix ?: return null
		val longitude = location.longitudeE7 / 10_000_000.0

		val resource = if (longitude >= 0) {
			R.string.longitude_value_east
		} else {
			R.string.longitude_value_west
		}

		return context.getString(
				resource,
				Assist.coordinateToString(abs(longitude))
		)
	}
}
