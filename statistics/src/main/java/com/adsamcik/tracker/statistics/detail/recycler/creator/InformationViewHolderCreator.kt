package com.adsamcik.tracker.statistics.detail.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.adsamcik.tracker.statistics.detail.recycler.viewholder.InformationViewHolder

class InformationViewHolderCreator : StatisticsViewHolderCreator {
	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<StatisticsDetailData> {
		val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.layout_stats_detail_item, parent, false)
		@Suppress("unchecked_cast")
		return InformationViewHolder(view) as StyleMultiTypeViewHolder<StatisticsDetailData>
	}
}

