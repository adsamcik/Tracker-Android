package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.WifiData
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

class WifiTrackerInfo(var wifiData: WifiData) : TrackerInfo(NAME_RESOURCE, R.drawable.ic_outline_network_wifi_24px) {
	override fun bindContent(holder: TrackerInfoAdapter.ViewHolder) {
		setBoldText(holder) { title, value ->
			title.setText(R.string.in_range)
			value.text = wifiData.inRange.size.toString()
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.wifi
	}
}