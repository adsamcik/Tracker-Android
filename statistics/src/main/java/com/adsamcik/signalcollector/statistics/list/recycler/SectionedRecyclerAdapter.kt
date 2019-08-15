package com.adsamcik.signalcollector.statistics.list.recycler

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.style.IViewChange
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionedRecyclerViewAdapter


class SectionedRecyclerAdapter : SectionedRecyclerViewAdapter(), IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		super.onBindViewHolder(holder, position)
		onViewChangedListener?.invoke(holder.itemView)
	}

}
