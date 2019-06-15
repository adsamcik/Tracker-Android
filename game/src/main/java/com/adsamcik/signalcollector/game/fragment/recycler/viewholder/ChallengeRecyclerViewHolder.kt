package com.adsamcik.signalcollector.game.fragment.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.game.fragment.recycler.data.ChallengeRecyclerData

class ChallengeRecyclerViewHolder(rootView: View, title: TextView, recycler: RecyclerView) : ListRecyclerViewHolder<ChallengeRecyclerData>(rootView, title, recycler) {
	override fun bindRecycler(value: ChallengeRecyclerData, recycler: RecyclerView) {
		recycler.adapter = value.dataAdapter
	}
}