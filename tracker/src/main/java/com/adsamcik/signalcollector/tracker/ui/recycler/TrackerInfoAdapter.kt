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
		update<SessionTrackerInfo>(true, SessionTrackerInfo.NAME_RESOURCE, { SessionTrackerInfo(sessionData) }, { it.session = sessionData })

		val location = collectionData.location
		update<LocationTrackerInfo>(location != null, LocationTrackerInfo.NAME_RESOURCE, { LocationTrackerInfo(location!!) }, { it.location = location!! })

		val activity = collectionData.activity
		update<ActivityTrackerInfo>(activity != null, ActivityTrackerInfo.NAME_RESOURCE, { ActivityTrackerInfo(activity!!) }, { it.activity = activity!! })

		val wifi = collectionData.wifi
		update<WifiTrackerInfo>(wifi != null, WifiTrackerInfo.NAME_RESOURCE, { WifiTrackerInfo(wifi!!) }, { it.wifiData = wifi!! })

		val cell = collectionData.cell
		update<CellTrackerInfo>(cell != null, CellTrackerInfo.NAME_RESOURCE, { CellTrackerInfo(cell!!) }, { it.cellData = cell!! })
	}

	private inline fun <T> update(isAvailable: Boolean, nameRes: Int, factory: () -> TrackerInfo, updater: (T) -> Unit) {
		val index = data.indexOfFirst { it.nameRes == nameRes }
		if (isAvailable) {
			if (index >= 0) {
				@Suppress("UNCHECKED_CAST")
				updater(data[index] as T)
				notifyItemChanged(index)
			} else {
				val insertedAt = data.size
				data.add(factory())
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