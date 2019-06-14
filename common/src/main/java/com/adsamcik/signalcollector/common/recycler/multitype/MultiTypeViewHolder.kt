package com.adsamcik.signalcollector.common.recycler.multitype

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.color.ColorController

abstract class MultiTypeViewHolder<Data : BaseMultiTypeData>(rootView: View) : RecyclerView.ViewHolder(rootView) {
	abstract fun bind(value: Data, colorController: ColorController)

	open fun onRecycle(colorController: ColorController) {}
}