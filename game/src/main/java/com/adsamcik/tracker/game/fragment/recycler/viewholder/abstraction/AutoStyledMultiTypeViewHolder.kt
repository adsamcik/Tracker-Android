package com.adsamcik.tracker.game.fragment.recycler.viewholder.abstraction

import android.view.View
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleView

@Deprecated("Use Compose LazyColumn with Material 3 theming instead of legacy View-based adapters with StyleController")
internal abstract class AutoStyledMultiTypeViewHolder<DataType : GameRecyclerData>(
		rootView: View,
		protected val layer: Int
) : StyleMultiTypeViewHolder<DataType>(
		rootView
) {
	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun bind(data: DataType, styleController: StyleController) {
		styleController.watchView(StyleView(itemView, layer))
	}

	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun onRecycle(styleController: StyleController) {
		super.onRecycle(styleController)
		styleController.stopWatchingView(itemView)
	}
}
