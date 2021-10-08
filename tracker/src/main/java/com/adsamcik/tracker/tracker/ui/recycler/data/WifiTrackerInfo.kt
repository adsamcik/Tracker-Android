package com.adsamcik.tracker.tracker.ui.recycler.data

import com.adsamcik.tracker.shared.base.data.WifiData
import com.adsamcik.tracker.tracker.R

class WifiTrackerInfo(var wifiData: WifiData) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int = R.drawable.ic_outline_network_wifi_24px

	override fun bindContent(holder: InfoFieldHolder) {
		holder.getBoldText().apply {
			title.setText(R.string.in_range)
			value.text = wifiData.inRange.size.toString()
		}
	}

	companion object {
		val NAME_RESOURCE: Int = R.string.wifi
	}
}
