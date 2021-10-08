package com.adsamcik.tracker.shared.utils.multitype

import android.view.View
import com.adsamcik.recycler.adapter.implementation.multitype.BaseMultiTypeData
import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController

/**
 * View holder for multiple type style adapters.
 */
abstract class StyleMultiTypeViewHolder<Data : BaseMultiTypeData>(
		rootView: View
) : MultiTypeViewHolder<Data>(rootView) {
	/**
	 * Bind is not called
	 */
	override fun bind(data: Data): Unit = Unit

	/**
	 * Called instead of standard bind to allow style controller mapping.
	 */
	abstract fun bind(data: Data, styleController: StyleController)

	/**
	 * Called when view is recycler.
	 */
	open fun onRecycle(styleController: StyleController) {}
}
