package com.adsamcik.tracker.tracker.notification

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.database.PreferenceDatabase
import com.adsamcik.tracker.tracker.notification.component.ActivityNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.AltitudeNotificationComponent
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
import com.adsamcik.tracker.tracker.notification.component.SpeedNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.StartTimeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.WiFiCountNotificationComponent

object TrackerNotificationProvider {
	internal val internalActiveList = listOf(
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
			SpeedNotificationComponent(),
			StartTimeNotificationComponent(),
			WiFiCountNotificationComponent()
	)

	val activeComponentList: List<BaseTrackerNotificationComponent> get() = internalActiveList

	@WorkerThread
	fun updatePreferences(context: Context) {
		val dao = PreferenceDatabase.database(context).notificationDao
		val preferences = dao.getAll()

		internalActiveList.forEach { component ->
			val preference = preferences.find { it.id == component.id }
			if (preference == null) {
				component.preference = component.defaultPreference
			} else {
				component.preference = preference
			}
		}

		internalActiveList.sortedBy { it.preference.order }
				.forEachIndexed { index, trackerNotificationComponent ->
					trackerNotificationComponent.preference =
							trackerNotificationComponent.preference.copy(
									order = index
							)
				}
	}
}
