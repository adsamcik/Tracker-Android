package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.common.extension.formatDistance
import com.adsamcik.signalcollector.common.extension.formatSpeed
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.R

class LocationTrackerInfo(var location: Location) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int = R.drawable.ic_outline_location_on_24px

	override fun bindContent(holder: InfoFieldHolder) {
		val context = holder.context
		val resources = holder.resources
		val lengthSystem = Preferences.getLengthSystem(context)

		holder.getBoldText().apply {
			value.text = Assist.coordinateToString(location.latitude)
			title.setText(R.string.latitude)
		}

		holder.getBoldText().apply {
			value.text = Assist.coordinateToString(location.longitude)
			title.setText(R.string.longitude)
		}

		location.altitude?.let {
			holder.getBoldText().apply {
				value.text = resources.formatDistance(it, 1, lengthSystem)
				title.setText(R.string.altitude)
			}
		}

		location.speed?.let {
			holder.getBoldText().apply {
				value.text = resources.formatSpeed(it, 1, lengthSystem, Preferences.getSpeedFormat(context))
				title.setText(R.string.speed)
			}
		}

		location.horizontalAccuracy?.let {
			holder.getBoldText().apply {
				value.text = resources.formatDistance(it, 1, lengthSystem)
				title.setText(R.string.horizontal_accuracy)
			}
		}

		location.verticalAccuracy?.let {
			holder.getBoldText().apply {
				value.text = resources.formatDistance(it, 1, lengthSystem)
				title.setText(R.string.vertical_accuracy)
			}
		}
	}

	companion object {
		val NAME_RESOURCE = R.string.location
	}
}
