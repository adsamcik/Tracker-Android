package com.adsamcik.tracker.tracker.ui.recycler.data

import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.extension.formatSpeed
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.tracker.R

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
