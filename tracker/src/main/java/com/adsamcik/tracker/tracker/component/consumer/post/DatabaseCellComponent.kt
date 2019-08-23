package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.common.data.BaseLocation
import com.adsamcik.tracker.common.data.CellData
import com.adsamcik.tracker.common.data.CellInfo
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.database.dao.CellLocationDao
import com.adsamcik.tracker.common.database.dao.CellOperatorDao
import com.adsamcik.tracker.common.database.data.DatabaseCellLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class DatabaseCellComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var cellLocationDao: CellLocationDao? = null
	private var cellOperatorDao: CellOperatorDao? = null


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val cellData = collectionData.cell
		if (cellData != null) {
			val location = collectionData.location
			if (location != null) {
				saveLocation(collectionData.time, cellData, location)
			}

			saveOperator(cellData)
		}
	}

	private fun saveOperator(cell: CellInfo) {
		requireNotNull(cellOperatorDao).insert(cell.networkOperator)
	}

	private fun saveOperator(cell: CellData) {
		cell.registeredCells.forEach { saveOperator(it) }
	}

	private fun saveLocation(time: Long, cell: CellInfo, location: Location) {
		val cellLocation = DatabaseCellLocation(
				time,
				cell.networkOperator.mcc,
				cell.networkOperator.mnc,
				cell.cellId,
				cell.type,
				cell.asu,
				BaseLocation(location)
		)

		requireNotNull(cellLocationDao).insert(cellLocation)
	}

	private fun saveLocation(time: Long, cell: CellData, location: Location) {
		cell.registeredCells.forEach { saveLocation(time, it, location) }
	}

	override suspend fun onDisable(context: Context) {
		cellLocationDao = null
		cellOperatorDao = null
	}

	override suspend fun onEnable(context: Context) {
		val database = AppDatabase.getDatabase(context)
		cellLocationDao = database.cellLocationDao()
		cellOperatorDao = database.cellOperatorDao()
	}
}

