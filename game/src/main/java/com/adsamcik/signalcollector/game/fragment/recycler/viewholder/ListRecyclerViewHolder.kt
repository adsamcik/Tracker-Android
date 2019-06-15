package com.adsamcik.signalcollector.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorView
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.game.fragment.recycler.data.ListRecyclerData

abstract class ListRecyclerViewHolder<DataType: ListRecyclerData<*>>(rootView: View, private val title: TextView, private val recycler: RecyclerView) : MultiTypeViewHolder<DataType>(rootView) {
	abstract fun bindRecycler(value: DataType, recycler: RecyclerView)

	override fun bind(value: DataType, colorController: ColorController) {
		title.setText(value.title)
		bindRecycler(value, recycler)

		//todo layer should be better handled
		colorController.watchRecyclerView(ColorView(recycler, 1, true))
	}

	override fun onRecycle(colorController: ColorController) {
		super.onRecycle(colorController)
		colorController.stopWatchingRecyclerView(recycler)
	}
}