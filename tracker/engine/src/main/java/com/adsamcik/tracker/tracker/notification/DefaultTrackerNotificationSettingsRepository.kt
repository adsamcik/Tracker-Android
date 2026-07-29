package com.adsamcik.tracker.tracker.notification

import com.adsamcik.tracker.shared.base.database.dao.NotificationPreferenceDao
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine adapter for the API-owned notification settings boundary.
 */
@Singleton
class DefaultTrackerNotificationSettingsRepository @Inject constructor(
	private val notificationPreferenceDao: NotificationPreferenceDao,
) : TrackerNotificationSettingsRepository {
	private val accessMutex = Mutex()

	override suspend fun loadSettings(): List<TrackerNotificationSetting> =
		accessMutex.withLock {
			TrackerNotificationProvider.applyPreferences(notificationPreferenceDao.getAll())
			TrackerNotificationProvider.configuredComponents().map { component ->
				TrackerNotificationSetting(
					id = component.id,
					titleRes = component.titleRes,
					isInTitle = component.preference.isInTitle,
					isInContent = component.preference.isInContent,
				)
			}
		}

	override suspend fun saveSettings(
		settingsInDisplayOrder: List<TrackerNotificationSetting>,
	) {
		accessMutex.withLock {
			val configuredComponents = TrackerNotificationProvider.configuredComponents()
			val componentIds = configuredComponents.map(TrackerNotificationComponent::id)
			val suppliedIds = settingsInDisplayOrder.map(TrackerNotificationSetting::id)

			require(suppliedIds.size == suppliedIds.distinct().size) {
				"Notification settings must contain unique IDs"
			}
			require(suppliedIds.toSet() == componentIds.toSet()) {
				"Notification settings must contain the complete tracker notification catalog"
			}

			val preferences = settingsInDisplayOrder.mapIndexed { index, setting ->
				NotificationPreference(
					id = setting.id,
					order = index,
					isInTitle = setting.isInTitle,
					isInContent = setting.isInContent,
				)
			}
			notificationPreferenceDao.upsert(preferences)
			TrackerNotificationProvider.applyPreferences(preferences)
		}
	}
}
