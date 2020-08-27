package com.adsamcik.tracker.statistics.wifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.adapter.implementation.sort.BaseSortAdapter
import com.adsamcik.recycler.adapter.implementation.sort.callback.SortCallback
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
import com.adsamcik.tracker.shared.base.extension.formatAsShortDateTime
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * Wi-Fi Recycler adapter
 */
class WifiRecyclerAdapter
	: BaseSortAdapter<DatabaseWifiData, RecyclerView.ViewHolder>(DatabaseWifiData::class.java),
		IViewChange,
		CoroutineScope {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Main + job

	override fun getItemViewType(position: Int): Int {
		return when (position) {
			0 -> SUMMARY_TYPE
			else -> NORMAL_TYPE
		}
	}

	override fun addAll(collection: Collection<DatabaseWifiData>) {
		super.addAll(collection)
		// fixes issue with header being pushed down after addition, because of sorting
		notifyDataSetChanged()
	}

	override fun getItemCount(): Int {
		return super.getItemCount() + 2
	}

	private fun bindNormalViewHolder(holder: RecyclerWifiViewHolder, position: Int) {
		when (position) {
			0 -> throw IllegalArgumentException("Position 0 should be summary.")
			1 -> {
				val resources = holder.itemView.resources
				holder.apply {
					bssid.text = resources.getString(R.string.wifilist_title_bssid)
					ssid.text = resources.getString(R.string.wifilist_title_ssid)
					capabilities.text = resources.getString(R.string.wifilist_title_capabilities)
					frequency.text = resources.getString(R.string.wifilist_title_frequency)
					firstSeen.text = resources.getString(R.string.wifilist_title_first_seen)
					lastSeen.text = resources.getString(R.string.wifilist_title_last_seen)
				}
			}
			else -> {
				val item = getItem(position - 2)
				val resources = holder.itemView.resources
				holder.apply {
					bssid.text = item.bssid
					ssid.text = item.ssid
					capabilities.text = item.capabilities
					frequency.text = resources.getString(
							R.string.wifilist_item_frequency,
							item.frequency
					)
					firstSeen.text = item.firstSeen.formatAsShortDateTime()
					lastSeen.text = item.lastSeen.formatAsShortDateTime()
				}
			}
		}
	}

	private fun bindSummaryViewHolder(holder: RecyclerSummaryViewHolder) {
		val resources = holder.itemView.resources
		holder.apply {
			count.text = resources.getString(R.string.wifilist_count, super.getItemCount())
		}
	}

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		when (val type = getItemViewType(position)) {
			NORMAL_TYPE -> bindNormalViewHolder(holder as RecyclerWifiViewHolder, position)
			SUMMARY_TYPE -> bindSummaryViewHolder(holder as RecyclerSummaryViewHolder)
			else -> throw IllegalStateException("Unknown view type with value $type")
		}
	}

	override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	private fun inflate(@LayoutRes layout: Int, parent: ViewGroup): View {
		val inflater = LayoutInflater.from(parent.context)
		return inflater.inflate(layout, parent, false)
	}

	private fun createNormalViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
		val rootView = inflate(R.layout.layout_wifi_item, parent)
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

	private fun createSummaryViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
		val rootView = inflate(R.layout.layout_wifi_summary, parent)
		return RecyclerSummaryViewHolder(
				rootView,
				rootView.findViewById(R.id.summary_count)
		)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
		return when (viewType) {
			NORMAL_TYPE -> createNormalViewHolder(parent)
			SUMMARY_TYPE -> createSummaryViewHolder(parent)
			else -> throw IllegalStateException("Unknown view type with value $viewType")
		}
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

	companion object {
		private const val SUMMARY_TYPE = 0
		private const val NORMAL_TYPE = 1
	}
}

/**
 * Recycler Wi-Fi View Holder.
 */
@Suppress("LongParameterList")
class RecyclerWifiViewHolder(
		root: View,
		val bssid: TextView,
		val ssid: TextView,
		val capabilities: TextView,
		val frequency: TextView,
		val firstSeen: TextView,
		val lastSeen: TextView
) : RecyclerView.ViewHolder(root)

/**
 * Recycler Summary View Holder.
 */
class RecyclerSummaryViewHolder(
		root: View,
		val count: TextView
) : RecyclerView.ViewHolder(root)
