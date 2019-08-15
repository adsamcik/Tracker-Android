package com.adsamcik.signalcollector.statistics.detail.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolderCreator
import com.adsamcik.signalcollector.statistics.R
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.viewholder.InformationViewHolder

class InformationViewHolderCreator : MultiTypeViewHolderCreator<StatisticDetailData> {
	override fun createViewHolder(parent: ViewGroup): MultiTypeViewHolder<StatisticDetailData> {
		val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.layout_stats_detail_item, parent, false)
		@Suppress("unchecked_cast")
		return InformationViewHolder(view) as MultiTypeViewHolder<StatisticDetailData>
	}
}

