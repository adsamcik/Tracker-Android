package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.fragment.recycler.data.StepsRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.viewholder.abstraction.AutoStyledMultiTypeViewHolder
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.utils.style.StyleController

/**
 * View holder for steps in game recycler.
 */
internal class StepsViewHolder(
		rootView: View,
		layer: Int,
		private val dailyStepsTitle: AppCompatTextView,
		private val dailyStepsText: AppCompatTextView,
		private val weeklyStepsTitle: AppCompatTextView,
		private val weeklyStepsText: AppCompatTextView
) : AutoStyledMultiTypeViewHolder<StepsRecyclerData>(rootView, layer) {
	override fun bind(data: StepsRecyclerData, styleController: StyleController) {
		super.bind(data, styleController)
		val resources = itemView.context.resources
		dailyStepsTitle.text = resources.getString(R.string.goals_steps_daily)
		dailyStepsText.text = resources.getString(
				R.string.goals_value,
				data.stepsToday.formatReadable(),
				data.goalDay.formatReadable()
		)
		weeklyStepsTitle.text = resources.getString(R.string.goals_steps_weekly)
		weeklyStepsText.text = resources.getString(
				R.string.goals_value,
				data.stepsThisWeek.formatReadable(),
				data.goalWeek.formatReadable()
		)
	}
}
