package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.CellData
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

class CellTrackerInfo(var cellData: CellData) : TrackerInfo(NAME_RESOURCE, R.drawable.ic_outline_network_cell_24px) {
	override fun bindContent(holder: TrackerInfoAdapter.ViewHolder) {
		val resources = holder.itemView.context.resources
		cellData.registeredCells.forEach {
			setBoldText(holder) { title, value ->
				title.text = it.operatorName
				value.text = resources.getString(R.string.cell_current, it.type.name, it.dbm)

			}
		}

		setBoldText(holder) { title, value ->
			title.setText(R.string.in_range)
			value.text = cellData.totalCount.toString()
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.cell
	}
}