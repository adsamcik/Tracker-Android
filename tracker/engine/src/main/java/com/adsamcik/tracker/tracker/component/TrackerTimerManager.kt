package com.adsamcik.tracker.tracker.component

import android.content.Context
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.tracker.source.model.LocationBackend

/**
 * Reads the historical timer preference only to preserve the selected location backend.
 * Phase 10 removed trigger construction; source-native runtimes own all registrations.
 */
object TrackerTimerManager {
	/** Preserves the existing backend preference when location ownership moves to source runtime. */
	internal suspend fun getSelectedLocationBackend(context: Context): LocationBackend =
		when (getSelectedKey(context)) {
			FUSED_LOCATION_TRIGGER_KEY -> LocationBackend.FUSED
			else -> LocationBackend.FRAMEWORK
		}

	private suspend fun getSelectedKey(context: Context): String {
		return Preferences(context)
			.fetchString(PreferenceKeys.TRACKER_TIMER)
			?: FUSED_LOCATION_TRIGGER_KEY
	}

	private const val FUSED_LOCATION_TRIGGER_KEY = "FusedLocationCollectionTrigger"
}
