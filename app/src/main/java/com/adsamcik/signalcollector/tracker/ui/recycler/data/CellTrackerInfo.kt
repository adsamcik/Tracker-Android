package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.CellData

class CellTrackerInfo(var cellData: CellData) : TrackerInfo(NAME_RESOURCE, R.drawable.ic_outline_network_cell_24px) {
	override fun bindContent(holder: InfoFieldHolder) {
		val resources = holder.resources
		cellData.registeredCells.forEach {
			holder.getBoldText().apply {
				title.text = it.operatorName
				value.text = resources.getString(R.string.cell_current, it.type.name, it.dbm)
			}
		}

		holder.getBoldText().apply {
			title.setText(R.string.in_range)
			value.text = cellData.totalCount.toString()
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.cell
	}
}