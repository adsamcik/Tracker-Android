package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.database.data.DatabaseWifiData
import com.adsamcik.signalcollector.tracker.data.CollectionData
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.TrackerSession
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TrackerDataComponent(context: Context): PostTrackerComponent {
	val database = AppDatabase.getDatabase(context)

	private val data: MutableCollectionData = requestNewData()

	fun requestNewData(): MutableCollectionData {
		return MutableCollectionData(System.currentTimeMillis())
	}

	override fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData) {
		GlobalScope.launch {
			val trackedLocation = data.location
			var locationId: Long? = null
			val activity = data.activity
			if (trackedLocation != null && activity != null) {
				locationId = database.locationDao().insert(trackedLocation.toDatabase(activity))
			}

			val cellData = data.cell
			val cellDao = database.cellDao()
			cellData?.registeredCells?.map { DatabaseCellData(locationId, data.time, data.time, it) }?.let { cellDao.upsert(it) }

			val wifiData = data.wifi
			if (wifiData != null) {
				val wifiDao = database.wifiDao()

				val estimatedWifiLocation = com.adsamcik.signalcollector.tracker.data.Location(wifiData.location)
				wifiData.inRange.map { DatabaseWifiData(estimatedWifiLocation, it) }.let { wifiDao.upsert(it) }
			}
		}
	}

}