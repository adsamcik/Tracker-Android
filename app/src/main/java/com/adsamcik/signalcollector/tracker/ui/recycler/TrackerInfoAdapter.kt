package com.adsamcik.signalcollector.tracker.ui.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.misc.extension.remove
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData
import com.adsamcik.signalcollector.tracker.ui.recycler.data.LocationTrackerInfo
import com.adsamcik.signalcollector.tracker.ui.recycler.data.TrackerInfo

class TrackerInfoAdapter : RecyclerView.Adapter<TrackerInfoAdapter.ViewHolder>() {
	private val data = mutableListOf<TrackerInfo>()

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val layoutInflater = LayoutInflater.from(parent.context)
		val root = layoutInflater.inflate(R.layout.layout_tracker_card, parent, false)
		val content = root.findViewById<ViewGroup>(R.id.content)
		val title = root.findViewById<AppCompatTextView>(R.id.tracker_item_title)
		return ViewHolder(content, title, root)
	}

	override fun getItemCount(): Int = data.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		data[position].bind(holder)
	}

	fun update(collectionData: CollectionData) {
		val location = collectionData.location
		if (location != null) {
			val index = data.indexOfFirst { it.nameRes == R.string.location }
			if(index >= 0) {
				(data[index] as LocationTrackerInfo).apply {
					this.location = location
				}
			} else {
				data.add(LocationTrackerInfo(location))
			}
		} else {
			data.remove { it.nameRes == R.string.location }
		}
	}

	class ViewHolder(val content: ViewGroup, val title: AppCompatTextView, root: View) : RecyclerView.ViewHolder(root)
}