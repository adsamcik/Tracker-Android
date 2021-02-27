package com.adsamcik.tracker.game.fragment.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.viewholder.PointsRecyclerViewHolder
import com.adsamcik.tracker.game.fragment.recycler.viewholder.StepsViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolderCreator

/**
 * Creator for challenge recycler items. Creates a nested recycler view.
 */
class StepsCreator(private val layer: Int) : StyleMultiTypeViewHolderCreator<GameRecyclerData> {
	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<GameRecyclerData> {
		val context = parent.context
		val layoutInflater = LayoutInflater.from(context)
		val rootView = layoutInflater.inflate(R.layout.layout_points_step_goals, parent, false)

		val dailyRoot = rootView.findViewById<ViewGroup>(R.id.steps_goal_day)
		val weeklyRoot = rootView.findViewById<ViewGroup>(R.id.steps_goal_week)

		@Suppress("unchecked_cast")
		return StepsViewHolder(
				rootView,
				layer,
				dailyRoot.findViewById(R.id.title),
				dailyRoot.findViewById(R.id.value),
				weeklyRoot.findViewById(R.id.title),
				weeklyRoot.findViewById(R.id.value)
		) as StyleMultiTypeViewHolder<GameRecyclerData>
	}
}

