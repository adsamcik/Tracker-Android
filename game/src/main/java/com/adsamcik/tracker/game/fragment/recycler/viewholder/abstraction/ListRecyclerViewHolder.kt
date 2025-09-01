package com.adsamcik.tracker.game.fragment.recycler.viewholder.abstraction

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.ListRecyclerData
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleController

/**
 * View Holder for list based items in Game Recycler View.
 */
@Deprecated("Use Compose LazyColumn with Material 3 theming instead of legacy View-based adapters with StyleController")
abstract class ListRecyclerViewHolder<DataType : ListRecyclerData<*>>(
		rootView: View,
		private val title: TextView,
		private val recycler: RecyclerView,
		private val layer: Int
) : StyleMultiTypeViewHolder<DataType>(rootView) {
	/**
	 * Binds recycler view
	 */
	abstract fun bindRecycler(value: DataType, recycler: RecyclerView)

	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun bind(data: DataType, styleController: StyleController) {
		title.setText(data.title)

		bindRecycler(data, recycler)

		styleController.watchRecyclerView(RecyclerStyleView(recycler, layer))
	}

	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun onRecycle(styleController: StyleController) {
		styleController.stopWatchingRecyclerView(recycler)
	}
}

