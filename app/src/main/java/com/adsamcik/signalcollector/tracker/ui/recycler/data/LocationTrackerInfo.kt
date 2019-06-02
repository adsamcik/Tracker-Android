package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.misc.extension.formatDistance
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

class LocationTrackerInfo(var location: Location) : TrackerInfo(NAME_RESOURCE, R.drawable.ic_outline_location_on_24px) {
	override fun bindContent(holder: TrackerInfoAdapter.ViewHolder) {
		val context = holder.itemView.context
		val resources = context.resources

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
				value.text = resources.formatDistance(altitude, 1, Preferences.getLengthSystem(context))
				title.setText(R.string.altitude)
			}
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.location
	}

}