package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.WifiData

class WifiTrackerInfo(var wifiData: WifiData) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int = R.drawable.ic_outline_network_wifi_24px

	override fun bindContent(holder: InfoFieldHolder) {
		holder.getBoldText().apply {
			title.setText(R.string.in_range)
			value.text = wifiData.inRange.size.toString()
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.wifi
	}
}