package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

class LocationTrackerInfo(var location: Location) : TrackerInfo(NAME_RESOURCE, R.drawable.ic_outline_location_on_24px) {
	override fun bindContent(holder: TrackerInfoAdapter.ViewHolder) {
		setBoldText(holder) { title, value ->
			value.text = Assist.coordinateToString(location.latitude)
			title.setText(R.string.latitude)
		}

		setBoldText(holder) { title, value ->
			value.text = Assist.coordinateToString(location.longitude)
			title.setText(R.string.longitude)
		}

		val altitude = location.altitude
		if (altitude != null) {
			setBoldText(holder) { title, value ->
				value.text = Assist.coordinateToString(altitude)
				title.setText(R.string.altitude)
			}
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.location
	}

}