package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.game.fragment.recycler.data.ChallengeRecyclerData

/**
 * View holder for challenges in game recycler.
 */
class ChallengeRecyclerViewHolder(
		rootView: View,
		title: TextView,
		recycler: RecyclerView,
		layer: Int
) :
		ListRecyclerViewHolder<ChallengeRecyclerData>(rootView, title, recycler, layer) {
	override fun bindRecycler(value: ChallengeRecyclerData, recycler: RecyclerView) {
		recycler.adapter = value.dataAdapter
	}
}

