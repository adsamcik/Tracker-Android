package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.recycler.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.common.style.RecyclerStyleView
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.game.fragment.recycler.data.ListRecyclerData

abstract class ListRecyclerViewHolder<DataType : ListRecyclerData<*>>(
		rootView: View,
		private val title: TextView,
		private val recycler: RecyclerView,
		private val layer: Int
) : StyleMultiTypeViewHolder<DataType>(rootView) {
	abstract fun bindRecycler(value: DataType, recycler: RecyclerView)

	override fun bind(data: DataType, styleController: StyleController) {
		title.setText(data.title)

		bindRecycler(data, recycler)

		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	}

	override fun onRecycle(styleController: StyleController) {
		styleController.stopWatchingRecyclerView(recycler)
	}
}

