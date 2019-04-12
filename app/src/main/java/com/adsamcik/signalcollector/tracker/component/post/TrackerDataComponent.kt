package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import com.adsamcik.signalcollector.database.AppDatabase
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.database.data.DatabaseWifiData
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TrackerDataComponent(context: Context) {
	val database = AppDatabase.getDatabase(context)

	private val data: MutableCollectionData = requestNewData()

	fun requestNewData(): MutableCollectionData {
		return MutableCollectionData(System.currentTimeMillis())
	}


	fun onSaveData(data: MutableCollectionData) {
		GlobalScope.launch {
			val location = data.location
			var locationId: Long? = null
			val activity = data.activity
			if (location != null && activity != null)
				locationId = database.locationDao().insert(location.toDatabase(activity))

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