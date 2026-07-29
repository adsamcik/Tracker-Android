package com.adsamcik.tracker.tracker.notification

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.database.PreferenceDatabase
import com.adsamcik.tracker.shared.base.database.data.NotificationPreference
import com.adsamcik.tracker.tracker.notification.component.ActivityNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.AltitudeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.BatteryNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.CellCountNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.CellCurrentNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.CollectionCountNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.DistanceInVehicleNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.DistanceNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.DistanceOnFootNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.DurationNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.HorizontalAccuracyNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.LastUpdateNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.LatitudeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.LongitudeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.SkiNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.SpeedNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.StartTimeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.WiFiCountNotificationComponent

internal object TrackerNotificationProvider {
	private val components = listOf(
			ActivityNotificationComponent(),
			AltitudeNotificationComponent(),
			CellCountNotificationComponent(),
			CellCurrentNotificationComponent(),
			CollectionCountNotificationComponent(),
			DistanceInVehicleNotificationComponent(),
			DistanceNotificationComponent(),
			DistanceOnFootNotificationComponent(),
			DurationNotificationComponent(),
			HorizontalAccuracyNotificationComponent(),
			LastUpdateNotificationComponent(),
			LatitudeNotificationComponent(),
			LongitudeNotificationComponent(),
			SkiNotificationComponent(),
			SpeedNotificationComponent(),
			StartTimeNotificationComponent(),
			WiFiCountNotificationComponent(),
			BatteryNotificationComponent()
	)

	@Synchronized
	fun configuredComponents(): List<TrackerNotificationComponent> =
		components.sortedBy { it.preference.order }

	@WorkerThread
	suspend fun updatePreferences(context: Context) {
		val dao = PreferenceDatabase.database(context).getNotificationDao()
		applyPreferences(dao.getAll())
	}

	/**
	 * Applies persisted rows to the canonical engine catalog.
	 *
	 * Unknown rows are ignored and missing rows use the component defaults. Sorting is stable,
	 * so the declaration order above remains the first-run order while all legacy defaults are 0.
	 */
	@Synchronized
	fun applyPreferences(preferences: List<NotificationPreference>) {
		val preferencesById = preferences.associateBy(NotificationPreference::id)
		components.forEach { component ->
			component.preference = preferencesById[component.id] ?: component.defaultPreference
		}

		components.sortedBy { it.preference.order }
			.forEachIndexed { index, component ->
				component.preference = component.preference.copy(order = index)
			}
	}
}
