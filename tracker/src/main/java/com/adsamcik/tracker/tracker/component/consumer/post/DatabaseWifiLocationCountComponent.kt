package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.dao.LocationWifiCountDao
import com.adsamcik.tracker.common.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseWifiLocationCountComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var wifiDao: LocationWifiCountDao? = null

	private var isEnabled = false


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		if (!isEnabled) return

		val wifiData = collectionData.wifi ?: return
		val tmpWifiLocation = wifiData.location ?: return

		val count = DatabaseLocationWifiCount(
				wifiData.time,
				tmpWifiLocation,
				wifiData.inRange.size.toShort()
		)

		requireNotNull(wifiDao).insert(count)
	}

	override suspend fun onDisable(context: Context) {
		wifiDao = null
		this.isEnabled = false
	}

	override suspend fun onEnable(context: Context) {
		val isEnabled = com.adsamcik.tracker.common.preferences.Preferences.getPref(context)
				.getBooleanRes(
						R.string.settings_wifi_location_count_enabled_key,
						R.string.settings_wifi_location_count_enabled_default
				)

		this.isEnabled = isEnabled
		if (isEnabled) {
			wifiDao = AppDatabase.database(context).wifiLocationCountDao()
		}
	}
}
