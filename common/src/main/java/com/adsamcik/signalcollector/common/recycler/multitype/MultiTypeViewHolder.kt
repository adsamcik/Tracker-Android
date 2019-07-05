package com.adsamcik.signalcollector.common.recycler.multitype

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.style.StyleController

abstract class MultiTypeViewHolder<Data : BaseMultiTypeData>(rootView: View) : RecyclerView.ViewHolder(rootView) {
	abstract fun bind(value: Data, styleController: StyleController)

	open fun onRecycle(styleController: StyleController) {}
}