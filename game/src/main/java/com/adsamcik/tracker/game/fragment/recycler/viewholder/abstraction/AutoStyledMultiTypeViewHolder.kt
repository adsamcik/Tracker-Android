package com.adsamcik.tracker.game.fragment.recycler.viewholder.abstraction

import android.view.View
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleView

internal abstract class AutoStyledMultiTypeViewHolder<DataType : GameRecyclerData>(
		rootView: View,
		protected val layer: Int
) : StyleMultiTypeViewHolder<DataType>(
		rootView
) {
	override fun bind(data: DataType, styleController: StyleController) {
		styleController.watchView(StyleView(itemView, layer))
	}

	override fun onRecycle(styleController: StyleController) {
		super.onRecycle(styleController)
		styleController.stopWatchingView(itemView)
	}
}
