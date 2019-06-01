package com.adsamcik.signalcollector.tracker.ui.recycler.data

import android.view.LayoutInflater
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.Location
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter
import kotlinx.android.synthetic.main.layout_tracker_info_bold_value.view.*

class LocationTrackerInfo(var location: Location) : TrackerInfo(R.string.location, R.drawable.ic_outline_location_on_24px) {
	override fun bindContent(holder: TrackerInfoAdapter.ViewHolder) {
		val content = holder.content
		content.removeAllViews()

		val resources = content.context.resources

		//todo improve - rewrite to kotlin
		val latitudeLayout = LayoutInflater.from(content.context).inflate(R.layout.layout_tracker_info_bold_value, content, false)
		latitudeLayout.value.text = Assist.coordinateToString(location.latitude)
		latitudeLayout.text.text = resources.getString(R.string.latitude)

		val longitudeLayout = LayoutInflater.from(content.context).inflate(R.layout.layout_tracker_info_bold_value, content, false)
		longitudeLayout.value.text = Assist.coordinateToString(location.longitude)
		longitudeLayout.text.text = resources.getString(R.string.longitude)

		content.addView(latitudeLayout)
		content.addView(longitudeLayout)
	}

}