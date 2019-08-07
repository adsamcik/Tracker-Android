package com.adsamcik.signalcollector.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.common.style.RecyclerStyleView
import com.adsamcik.signalcollector.common.style.StyleController
import com.adsamcik.signalcollector.game.fragment.recycler.data.ListRecyclerData

abstract class ListRecyclerViewHolder<DataType : ListRecyclerData<*>>(
		rootView: View,
		private val title: TextView,
		private val recycler: RecyclerView,
		private val layer: Int) : MultiTypeViewHolder<DataType>(rootView) {
	abstract fun bindRecycler(value: DataType, recycler: RecyclerView)

	override fun bind(value: DataType, styleController: StyleController) {
		title.setText(value.title)

		bindRecycler(value, recycler)

		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	}

	override fun onRecycle(styleController: StyleController) {
		styleController.stopWatchingRecyclerView(recycler)
	}
}