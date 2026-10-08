package com.adsamcik.tracker.tracker.notification

import android.content.Context
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference

internal abstract class TrackerNotificationComponent {
    open val source: SourceKind? = null

    fun isChosen(params: TrackingParamsState): Boolean = when (source) {
        SourceKind.LOCATION -> params.locationEnabled
        SourceKind.ACTIVITY -> params.activityEnabled
        SourceKind.STEPS -> params.stepsEnabled
        SourceKind.WIFI -> params.wifiEnabled
        SourceKind.CELL -> params.cellEnabled
        SourceKind.PRESSURE -> params.barometerEnabled
        null -> true
    }

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
			data: CollectionData,
			savedLocation: SavedLocationSummaryRead = SavedLocationSummaryRead.NotFound
	): String?
}
