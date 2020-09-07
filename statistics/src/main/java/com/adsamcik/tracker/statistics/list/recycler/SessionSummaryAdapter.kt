package com.adsamcik.tracker.statistics.list.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat

/**
 * Adapter for session summary
 */
class SessionSummaryAdapter
	: RecyclerView.Adapter<SessionSummaryAdapter.ViewHolder>(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	private val list = mutableListOf<Stat>()

	/**
	 * Add all stats from a collection.
	 */
	@MainThread
	fun addAll(list: Collection<Stat>) {
		this.list.addAll(list)
		notifyDataSetChanged()
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
		val resources = holder.itemView.resources
		holder.title.text = resources.getString(item.nameRes)
		holder.value.text = item.data.toString()
	}

	override fun onViewAttachedToWindow(holder: ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

	/**
	 * Session summary View Holder.
	 */
	class ViewHolder(
			root: View,
			val title: TextView,
			val value: TextView
	) : RecyclerView.ViewHolder(root)
}
