package com.adsamcik.tracker.game.fragment.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.fragment.recycler.data.GameRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.viewholder.PointsRecyclerViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolderCreator

/**
 * Creator for challenge recycler items. Creates a nested recycler view.
 */
class PointsRecyclerCreator(private val layer: Int) : StyleMultiTypeViewHolderCreator<GameRecyclerData> {
	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<GameRecyclerData> {
		val context = parent.context
		val layoutInflater = LayoutInflater.from(context)
		val rootView = layoutInflater.inflate(R.layout.layout_points_summary, parent, false)

		@Suppress("unchecked_cast")
		return PointsRecyclerViewHolder(
				rootView,
				layer,
				rootView.findViewById(R.id.text_points),
				rootView.findViewById(R.id.progress_circular_points)
		) as StyleMultiTypeViewHolder<GameRecyclerData>
	}
}

