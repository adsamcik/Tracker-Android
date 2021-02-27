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
internal class PointsRecyclerViewHolder(
		rootView: View,
		layer: Int,
		private val pointsText: AppCompatTextView,
		private val pointsProgress: CircularProgressIndicator
) : AutoStyledMultiTypeViewHolder<PointsRecyclerData>(rootView, layer) {
	override fun bind(data: PointsRecyclerData, styleController: StyleController) {
		super.bind(data, styleController)
		pointsText.text = data.pointsEarnedToday.toString()
		pointsProgress.setProgressCompat(data.pointsEarnedToday, true)
	}
}
