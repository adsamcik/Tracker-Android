package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.database.dao.CellDataDao
import com.adsamcik.signalcollector.common.database.dao.LocationDataDao
import com.adsamcik.signalcollector.common.database.dao.WifiDataDao
import com.adsamcik.signalcollector.common.database.data.DatabaseCellData
import com.adsamcik.signalcollector.common.database.data.DatabaseLocation
import com.adsamcik.signalcollector.common.database.data.DatabaseWifiData
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData
import com.adsamcik.signalcollector.common.data.TrackerSession
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TrackerDataComponent : PostTrackerComponent {
	private var locationDao: LocationDataDao? = null
	private var cellDao: CellDataDao? = null
	private var wifiDao: WifiDataDao? = null

	override fun onDisable(context: Context) {
		locationDao = null
		cellDao = null
		wifiDao = null
	}

	override fun onEnable(context: Context) {
		val database = AppDatabase.getDatabase(context)
		locationDao = database.locationDao()
		cellDao = database.cellDao()
		wifiDao = database.wifiDao()
	}

	override fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData) {
		GlobalScope.launch {
			val trackedLocation = collectionData.location
			var locationId: Long? = null
			val activity = collectionData.activity
			if (trackedLocation != null && activity != null) {
				locationId = locationDao!!.insert(DatabaseLocation(trackedLocation, activity))
			}

			val cellData = collectionData.cell
			val cellDao = cellDao!!
			cellData?.registeredCells?.map { DatabaseCellData(locationId, collectionData.time, collectionData.time, it) }?.let { cellDao.upsert(it) }

			val wifiData = collectionData.wifi
			if (wifiData != null) {
				val wifiDao = wifiDao!!

				val estimatedWifiLocation = com.adsamcik.signalcollector.common.data.Location(wifiData.location)
				wifiData.inRange.map { DatabaseWifiData(estimatedWifiLocation, it) }.let { wifiDao.upsert(it) }
			}
		}
	}

}