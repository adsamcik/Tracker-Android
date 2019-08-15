package com.adsamcik.tracker.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.game.fragment.recycler.data.ChallengeRecyclerData

class ChallengeRecyclerViewHolder(rootView: View, title: TextView, recycler: RecyclerView, layer: Int) :
		ListRecyclerViewHolder<ChallengeRecyclerData>(rootView, title, recycler, layer) {
	override fun bindRecycler(value: ChallengeRecyclerData, recycler: RecyclerView) {
		recycler.adapter = value.dataAdapter
	}
}

