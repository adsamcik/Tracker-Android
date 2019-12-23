package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.dao.WifiDataDao
import com.adsamcik.tracker.common.database.data.DatabaseWifiData
import com.adsamcik.tracker.common.preferences.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData


internal class DatabaseWifiComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var wifiDao: WifiDataDao? = null

	private var isEnabled = false

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		if (!isEnabled) return
		val wifiData = collectionData.wifi ?: return

		val tmpWifiLocation = wifiData.location
		val map = if (tmpWifiLocation != null) {
			val estimatedWifiLocation = Location(tmpWifiLocation)
			wifiData.inRange.map { DatabaseWifiData(wifiData.time, it, estimatedWifiLocation) }
		} else {
			wifiData.inRange.map { DatabaseWifiData(wifiData.time, it) }
		}

		requireNotNull(wifiDao).upsert(map)
	}

	override suspend fun onDisable(context: Context) {
		wifiDao = null
		this.isEnabled = false
	}

	override suspend fun onEnable(context: Context) {
		val isEnabled = com.adsamcik.tracker.common.preferences.Preferences.getPref(context)
				.getBooleanRes(
						R.string.settings_wifi_network_enabled_key,
						R.string.settings_wifi_network_enabled_default
				)

		this.isEnabled = isEnabled
		if (isEnabled) {
			wifiDao = AppDatabase.database(context).wifiDao()
		}
	}
}

