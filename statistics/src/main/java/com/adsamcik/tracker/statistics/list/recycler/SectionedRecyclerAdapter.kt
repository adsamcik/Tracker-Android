package com.adsamcik.tracker.statistics.list.recycler

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.utils.style.marker.IViewChange
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionedRecyclerViewAdapter


class SectionedRecyclerAdapter : SectionedRecyclerViewAdapter(),
		IViewChange {
	override var onViewChangedListener: ((View) -> Unit)? = null

	override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
		super.onViewAttachedToWindow(holder)
		onViewChangedListener?.invoke(holder.itemView)
	}

}
