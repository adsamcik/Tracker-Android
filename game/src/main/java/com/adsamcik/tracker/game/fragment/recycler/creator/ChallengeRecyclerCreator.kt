package com.adsamcik.tracker.game.fragment.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.decoration.MarginDecoration
import com.adsamcik.tracker.game.R
import com.adsamcik.tracker.game.fragment.recycler.data.abstraction.GameRecyclerData
import com.adsamcik.tracker.game.fragment.recycler.viewholder.ChallengeRecyclerViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolderCreator

/**
 * Creator for challenge recycler items. Creates a nested recycler view.
 */
class ChallengeRecyclerCreator : StyleMultiTypeViewHolderCreator<GameRecyclerData> {
	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<GameRecyclerData> {
		val context = parent.context
		val layoutInflater = LayoutInflater.from(context)
		val rootView = layoutInflater.inflate(R.layout.layout_card_challenge, parent, false)

		val recycler = rootView.findViewById<RecyclerView>(R.id.recycler).apply {
			layoutManager = LinearLayoutManager(context)
			addItemDecoration(MarginDecoration())
		}

		@Suppress("unchecked_cast")
		return ChallengeRecyclerViewHolder(
				rootView,
				rootView.findViewById(R.id.title),
				recycler,
				2
		) as StyleMultiTypeViewHolder<GameRecyclerData>
	}
}

