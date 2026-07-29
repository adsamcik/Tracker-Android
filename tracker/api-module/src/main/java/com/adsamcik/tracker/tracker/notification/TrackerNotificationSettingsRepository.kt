package com.adsamcik.tracker.tracker.notification

import androidx.annotation.StringRes

/**
 * User-facing configuration for one tracker notification field.
 *
 * The position of an item in the returned list is its display and notification order.
 * [id] is an opaque, persisted identifier and must not be derived or renamed by consumers.
 */
data class TrackerNotificationSetting(
	val id: String,
	@StringRes val titleRes: Int,
	val isInTitle: Boolean,
	val isInContent: Boolean,
)

/**
 * Context-free boundary for reading and updating the tracker notification layout.
 *
 * Runtime notification components and their persistence remain implementation details of
 * `:tracker:engine`.
 */
interface TrackerNotificationSettingsRepository {
	suspend fun loadSettings(): List<TrackerNotificationSetting>

	/**
	 * Persists the complete catalog in display order.
	 */
	suspend fun saveSettings(
		settingsInDisplayOrder: List<TrackerNotificationSetting>,
	)
}
