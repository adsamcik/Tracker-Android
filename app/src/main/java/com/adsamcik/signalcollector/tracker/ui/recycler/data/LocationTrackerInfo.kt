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

		location.altitude?.let {
			setBoldText(holder) { title, value ->
				value.text = resources.formatDistance(it, 1, Preferences.getLengthSystem(context))
				title.setText(R.string.altitude)
			}
		}

		location.speed?.let {
			setBoldText(holder) { title, value ->
				val speedFormatted = resources.formatDistance(it, 1, Preferences.getLengthSystem(context))
				value.text = resources.getString(R.string.speed_value, speedFormatted)
				title.setText(R.string.speed)
			}
		}

		location.horizontalAccuracy?.let {
			setBoldText(holder) { title, value ->
				value.text = resources.formatDistance(it, 1, Preferences.getLengthSystem(context))
				title.setText(R.string.horizontal_accuracy)
			}
		}

		location.verticalAccuracy?.let {
			setBoldText(holder) { title, value ->
				value.text = resources.formatDistance(it, 1, Preferences.getLengthSystem(context))
				title.setText(R.string.vertical_accuracy)
			}
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.location
	}

}