package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.tracker.game.fragment.recycler.data.PointsRecyclerData
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * View holder for challenges in game recycler.
 */
class PointsRecyclerViewHolder(
		rootView: View,
		private val layer: Int,
		private val pointsText: AppCompatTextView,
		private val pointsProgress: CircularProgressIndicator
) : StyleMultiTypeViewHolder<PointsRecyclerData>(rootView) {
	override fun bind(data: PointsRecyclerData, styleController: StyleController) {
		pointsText.text = data.pointsEarnedToday.toString()
		pointsProgress.setProgressCompat(data.pointsEarnedToday, true)
		styleController.watchView(StyleView(itemView, layer))
	}

	override fun onRecycle(styleController: StyleController) {
		super.onRecycle(styleController)
		styleController.stopWatchingView(itemView)
	}
}
