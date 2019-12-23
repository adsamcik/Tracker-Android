package com.adsamcik.tracker.common.recycler.multitype

import android.view.View
import com.adsamcik.recycler.adapter.implementation.multitype.BaseMultiTypeData
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeViewHolder
import com.adsamcik.tracker.common.style.StyleController

abstract class StyleMultiTypeViewHolder<Data : BaseMultiTypeData>(
		rootView: View
) : MultiTypeViewHolder<Data>(rootView) {
	/**
	 * Bind is not called
	 */
	override fun bind(data: Data) = Unit

	abstract fun bind(data: Data, styleController: StyleController)
	open fun onRecycle(styleController: StyleController) {}
}
