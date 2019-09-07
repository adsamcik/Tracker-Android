package com.adsamcik.tracker.tracker.notification

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.database.PreferenceDatabase
import com.adsamcik.tracker.tracker.notification.component.LatitudeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.LongitudeNotificationComponent
import com.adsamcik.tracker.tracker.notification.component.StartTimeNotificationComponent

object TrackerNotificationProvider {
	internal val internalActiveList = listOf(
			StartTimeNotificationComponent(),
			LatitudeNotificationComponent(),
			LongitudeNotificationComponent()
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
	}
}
