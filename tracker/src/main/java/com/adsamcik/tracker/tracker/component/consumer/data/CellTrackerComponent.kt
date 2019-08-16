package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import com.adsamcik.tracker.common.data.CellData
import com.adsamcik.tracker.common.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class CellTrackerComponent : DataTrackerComponent {
	override suspend fun onDisable(context: Context) {}

	override suspend fun onEnable(context: Context) {}

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(TrackerComponentRequirement.CELL)

	override suspend fun onDataUpdated(tempData: CollectionTempData, collectionData: MutableCollectionData) {
		val cellData = tempData.getCellData(this)
		collectionData.cell = CellData(cellData.registeredCells, cellData.cellScanData.size)
	}


}
