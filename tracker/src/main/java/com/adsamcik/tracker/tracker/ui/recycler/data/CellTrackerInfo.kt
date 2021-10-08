package com.adsamcik.tracker.tracker.ui.recycler.data

import com.adsamcik.tracker.shared.base.data.CellData
import com.adsamcik.tracker.shared.base.data.CellType
import com.adsamcik.tracker.tracker.R

class CellTrackerInfo(var cellData: CellData) : TrackerInfo(NAME_RESOURCE) {

	override val iconRes: Int
		get() = when (cellData.registeredCells.firstOrNull()?.type) {
			null, CellType.None -> R.drawable.signal_off
			CellType.Unknown -> R.drawable.signal
			CellType.GSM, CellType.CDMA -> R.drawable.signal_2g
			CellType.WCDMA -> R.drawable.signal_3g
			CellType.LTE -> R.drawable.signal_4g
			CellType.NR -> R.drawable.signal_5g
		}

	override fun bindContent(holder: InfoFieldHolder) {
		val resources = holder.resources
		cellData.registeredCells.forEach {
			holder.getBoldText().apply {
				title.text = it.networkOperator.name
				value.text = resources.getString(R.string.cell_current, it.type.name, it.dbm)
			}
		}

		holder.getBoldText().apply {
			title.setText(R.string.in_range)
			value.text = cellData.totalCount.toString()
		}
	}

	companion object {
		val NAME_RESOURCE: Int = R.string.cell
	}
}
