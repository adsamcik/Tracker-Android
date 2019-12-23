package com.adsamcik.tracker.tracker.ui.recycler.data

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.tracker.R
import java.text.NumberFormat
import java.util.*

class ActivityTrackerInfo(var activity: ActivityInfo) : TrackerInfo(NAME_RESOURCE) {
	override val iconRes: Int
		get() = activity.groupedActivity.iconRes

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
		val NAME_RESOURCE = R.string.tracker_activity_card_title
	}
}
