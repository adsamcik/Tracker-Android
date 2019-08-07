package com.adsamcik.signalcollector.tracker.ui.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.data.CollectionData
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.style.IViewChange
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.ui.recycler.data.*

class TrackerInfoAdapter : RecyclerView.Adapter<TrackerInfoAdapter.ViewHolder>(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null
	private val data = mutableListOf<TrackerInfo>()

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val layoutInflater = LayoutInflater.from(parent.context)
		val root = layoutInflater.inflate(R.layout.layout_tracker_card, parent, false)
		val content = root.findViewById<ViewGroup>(R.id.tracker_item_content)
		val title = root.findViewById<AppCompatTextView>(R.id.tracker_item_title)
		return ViewHolder(root, content, title)
	}

	override fun getItemCount(): Int = data.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		data[position].bind(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	fun update(collectionData: CollectionData, sessionData: TrackerSession) {
		update(sessionData,
				SessionTrackerInfo.NAME_RESOURCE,
				{ SessionTrackerInfo(it) },
				{ trackerInfo, value -> trackerInfo.session = value })

		val location = collectionData.location
		update(location,
				LocationTrackerInfo.NAME_RESOURCE,
				{ LocationTrackerInfo(it) },
				{ trackerInfo, value -> trackerInfo.location = value })

		val activity = collectionData.activity
		update(activity,
				ActivityTrackerInfo.NAME_RESOURCE,
				{ ActivityTrackerInfo(it) },
				{ trackerInfo, value -> trackerInfo.activity = value })

		val wifi = collectionData.wifi
		update(wifi,
				WifiTrackerInfo.NAME_RESOURCE,
				{ WifiTrackerInfo(it) },
				{ trackerInfo, value -> trackerInfo.wifiData = value })

		val cell = collectionData.cell
		update(cell,
				CellTrackerInfo.NAME_RESOURCE,
				{ CellTrackerInfo(it) },
				{ trackerInfo, value -> trackerInfo.cellData = value })
	}

	private inline fun <T, Z> update(value: Z?, nameRes: Int, factory: (Z) -> T, setter: (T, Z) -> Unit) {
		val index = data.indexOfFirst { it.nameRes == nameRes }
		if (value != null) {
			if (index >= 0) {
				@Suppress("UNCHECKED_CAST")
				setter.invoke(data[index] as T, value)
				notifyItemChanged(index)
			} else {
				val insertedAt = data.size
				data.add(factory(value) as TrackerInfo)
				notifyItemInserted(insertedAt)
			}
		} else if (index >= 0) {
			data.removeAt(index)
			notifyItemRemoved(index)
		}
	}

	class ViewHolder(root: View, val content: ViewGroup, val title: TextView, val fields: MutableList<InfoField> = mutableListOf()) : RecyclerView.ViewHolder(root)

	data class InfoField(val title: TextView, val value: TextView)
}