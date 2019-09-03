package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.dao.WifiDataDao
import com.adsamcik.tracker.common.database.data.DatabaseWifiData
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData


internal class DatabaseWifiComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var wifiDao: WifiDataDao? = null


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val wifiData = collectionData.wifi
		if (wifiData != null) {
			val tmpWifiLocation = wifiData.location
			val map = if (tmpWifiLocation != null) {
				val estimatedWifiLocation = Location(tmpWifiLocation)
				wifiData.inRange.map { DatabaseWifiData(wifiData.time, it, estimatedWifiLocation) }
			} else {
				wifiData.inRange.map { DatabaseWifiData(wifiData.time, it) }
			}

			requireNotNull(wifiDao).upsert(map)
		}
	}

	override suspend fun onDisable(context: Context) {
		wifiDao = null
	}

	override suspend fun onEnable(context: Context) {
		wifiDao = AppDatabase.database(context).wifiDao()
	}
}

