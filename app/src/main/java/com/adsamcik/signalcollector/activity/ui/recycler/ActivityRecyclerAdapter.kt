package com.adsamcik.signalcollector.activity.ui.recycler

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.adsamcik.recycler.SortableAdapter
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.color.IViewChange
import com.adsamcik.signalcollector.common.data.SessionActivity

class ActivityRecyclerAdapter : SortableAdapter<SessionActivity, RecyclerActivityViewHolder>(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: RecyclerActivityViewHolder, position: Int) {
		val item = getItem(position)
		val context = holder.itemView.context
		holder.textView.apply {
			text = item.name
			val icon = item.getIcon(context)
			setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
		}
		onViewChangedListener?.invoke(holder.itemView)
	}

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerActivityViewHolder {
		val inflater = LayoutInflater.from(parent.context)
		val rootView = inflater.inflate(R.layout.layout_activity_item, parent, false)
		return RecyclerActivityViewHolder(rootView, rootView as TextView)
	}
}