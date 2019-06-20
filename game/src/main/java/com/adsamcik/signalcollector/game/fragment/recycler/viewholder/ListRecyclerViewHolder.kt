package com.adsamcik.signalcollector.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.game.fragment.recycler.data.ListRecyclerData

abstract class ListRecyclerViewHolder<DataType : ListRecyclerData<*>>(
		rootView: View,
		private val title: TextView,
		private val recycler: RecyclerView,
		private val layer: Int) : MultiTypeViewHolder<DataType>(rootView) {
	abstract fun bindRecycler(value: DataType, recycler: RecyclerView)

	override fun bind(value: DataType, colorController: ColorController) {
		title.setText(value.title)

		bindRecycler(value, recycler)

		colorController.watchRecyclerView(ColorView(recycler, layer))
		colorController.watchView(ColorView(itemView, layer))
	}

	override fun onRecycle(colorController: ColorController) {
		colorController.stopWatchingView(itemView)
		colorController.stopWatchingRecyclerView(recycler)
	}
}