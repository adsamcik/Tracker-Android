package com.adsamcik.signalcollector.tracker.ui.recycler.data

import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.data.GroupedActivity
import java.text.NumberFormat
import java.util.*

class ActivityTrackerInfo(var activity: ActivityInfo) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int
		get() = when (activity.groupedActivity) {
			GroupedActivity.STILL -> R.drawable.ic_outline_still_24px
			GroupedActivity.ON_FOOT -> R.drawable.ic_directions_walk_white_24dp
			GroupedActivity.IN_VEHICLE -> R.drawable.ic_directions_car_white_24dp
			GroupedActivity.UNKNOWN -> R.drawable.ic_help_white_24dp
			else -> throw IllegalStateException()
		}

	override fun bindContent(holder: InfoFieldHolder) {
		val context = holder.context

		holder.getBoldText().apply {
			value.text = activity.getGroupedActivityName(context)
			title.setText(R.string.tracker_activity_title)
		}

		holder.getBoldText().apply {
			val percentageFormat = NumberFormat.getPercentInstance(Locale.getDefault())
			value.text = percentageFormat.format(activity.confidence / 100.0)
			title.setText(R.string.tracker_activity_confidence)
		}
	}

	companion object {
		const val NAME_RESOURCE = R.string.tracker_activity_card_title
	}
}