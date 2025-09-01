package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.tracker.game.fragment.recycler.data.PointsRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.viewholder.abstraction.AutoStyledMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * View holder for points in game recycler.
 */
@Deprecated("Use Compose LazyColumn with Material 3 theming instead of legacy View-based adapters with StyleController")
internal class PointsRecyclerViewHolder(
		rootView: View,
		layer: Int,
		private val pointsText: AppCompatTextView,
		private val pointsProgress: CircularProgressIndicator
) : AutoStyledMultiTypeViewHolder<PointsRecyclerData>(rootView, layer) {
	@Deprecated("Use Compose LazyColumn with Material 3 theming instead of StyleController-based ViewHolders")
	@Suppress("DEPRECATION")
	override fun bind(data: PointsRecyclerData, styleController: StyleController) {
		super.bind(data, styleController)
		pointsText.text = data.pointsEarnedToday.toString()
		pointsProgress.setProgressCompat(data.pointsEarnedToday, true)
	}
}
