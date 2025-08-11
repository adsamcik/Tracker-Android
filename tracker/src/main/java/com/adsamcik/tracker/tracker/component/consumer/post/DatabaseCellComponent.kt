package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.CellLocationDao
import com.adsamcik.tracker.shared.base.database.dao.CellOperatorDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseCellComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var cellLocationDao: CellLocationDao? = null
	private var cellOperatorDao: CellOperatorDao? = null
	private var scope: CoroutineScope? = null

	private fun toOwnLocation(location: android.location.Location?): Location? {
		return if (location != null) {
			Location(location)
		} else {
			null
		}
	}

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		// todo add tracking without location
		val cellData = collectionData.cell ?: return
		val location = collectionData.location ?: toOwnLocation(tempData.tryGetLocation())
		if (location != null) {
			saveLocation(collectionData.time, cellData, location)
		}

		saveOperator(cellData)
	}

	private fun saveOperator(cell: CellInfo) {
		val dao = cellOperatorDao ?: return
		scope?.launch(Dispatchers.IO) { try { dao.insert(cell.networkOperator) } catch (_: Throwable) {} }
	}

	private fun saveOperator(cell: CellData) {
		cell.registeredCells.forEach { saveOperator(it) }
	}

	private fun saveLocation(time: Long, cell: CellInfo, location: Location) {
		val dao = cellLocationDao ?: return
		val cellLocation = DatabaseCellLocation(
			time,
			cell.networkOperator.mcc,
			cell.networkOperator.mnc,
			cell.cellId,
			cell.type,
			cell.asu,
			BaseLocation(location)
		)
		scope?.launch(Dispatchers.IO) { try { dao.insert(cellLocation) } catch (_: Throwable) {} }
	}

	private fun saveLocation(time: Long, cell: CellData, location: Location) {
		cell.registeredCells.forEach { saveLocation(time, it, location) }
	}

	override suspend fun onDisable(context: Context) {
		scope?.cancel(); scope = null
		cellLocationDao = null
		cellOperatorDao = null
	}

	override suspend fun onEnable(context: Context) {
		val database = AppDatabase.database(context)
		cellLocationDao = database.cellLocationDao()
		cellOperatorDao = database.cellOperatorDao()
		scope = CoroutineScope(Job() + Dispatchers.Default)
	}
}

