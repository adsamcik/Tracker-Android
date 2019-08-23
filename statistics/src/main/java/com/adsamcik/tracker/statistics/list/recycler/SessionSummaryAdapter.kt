package com.adsamcik.tracker.statistics.list.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.StatData

class SessionSummaryAdapter : RecyclerView.Adapter<SessionSummaryAdapter.ViewHolder>(),
		IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val list = mutableListOf<StatData>()

	fun addAll(list: Collection<StatData>) {
		this.list.addAll(list)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.layout_recycler_title_value_item, parent, false)
		return ViewHolder(
				rootView,
				rootView.findViewById(R.id.text_title),
				rootView.findViewById(R.id.text_value)
		)
	}

	override fun getItemCount(): Int = list.size

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {
		val item = list[position]
		holder.title.text = item.id
		holder.value.text = item.value

		onViewChangedListener?.invoke(holder.itemView)
	}

	class ViewHolder(
			root: View,
			val title: TextView,
			val value: TextView
	) : RecyclerView.ViewHolder(root)
}
