package com.adsamcik.tracker.statistics.wifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.sort.BaseSortAdapter
import com.adsamcik.recycler.adapter.implementation.sort.callback.SortCallback
import com.adsamcik.tracker.common.database.data.DatabaseWifiData
import com.adsamcik.tracker.common.extension.formatAsShortDateTime
import com.adsamcik.tracker.common.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

class WifiRecyclerAdapter : BaseSortAdapter<DatabaseWifiData, RecyclerWifiViewHolder>(
		DatabaseWifiData::class.java
),
		IViewChange,
		CoroutineScope {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	override fun onBindViewHolder(holder: RecyclerWifiViewHolder, position: Int) {
		val item = getItem(position)
		val context = holder.itemView.context
		holder.apply {
			bssid.text = item.bssid
			ssid.text = item.ssid
			capabilities.text = item.capabilities
			frequency.text = item.frequency.toString()
			firstSeen.text = item.firstSeen.formatAsShortDateTime()
			lastSeen.text = item.lastSeen.formatAsShortDateTime()
		}
	}

	override fun onViewAttachedToWindow(holder: RecyclerWifiViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerWifiViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.layout_wifi_item, parent, false)
		return RecyclerWifiViewHolder(
				rootView,
				rootView.findViewById(R.id.bssid),
				rootView.findViewById(R.id.ssid),
				rootView.findViewById(R.id.capabilities),
				rootView.findViewById(R.id.frequency),
				rootView.findViewById(R.id.first_seen),
				rootView.findViewById(R.id.last_seen)
		)
	}

	override val sortCallback: SortCallback<DatabaseWifiData> = object : SortCallback<DatabaseWifiData> {
		override fun areContentsTheSame(a: DatabaseWifiData, b: DatabaseWifiData): Boolean {
			return a == b
		}

		override fun areItemsTheSame(a: DatabaseWifiData, b: DatabaseWifiData): Boolean {
			return a.bssid == b.bssid
		}

		override fun compare(a: DatabaseWifiData, b: DatabaseWifiData): Int {
			return a.bssid.compareTo(b.bssid)
		}

	}
}

class RecyclerWifiViewHolder(
		root: View,
		val bssid: TextView,
		val ssid: TextView,
		val capabilities: TextView,
		val frequency: TextView,
		val firstSeen: TextView,
		val lastSeen: TextView
) : RecyclerView.ViewHolder(root)
