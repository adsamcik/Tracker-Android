package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import android.view.View
import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.data.InformationStatisticsData
import kotlinx.android.synthetic.main.layout_stats_detail_item.view.*

class InformationViewHolder(view: View) : ViewHolder<InformationStatisticsData>(view) {
	private val iconView = view.icon
	private val titleView = view.title
	private val valueView = view.value

	override fun bind(value: InformationStatisticsData) {
		val resources = itemView.resources
		iconView.setImageDrawable(resources.getDrawable(value.iconRes, itemView.context.theme))
		titleView.setText(value.titleRes)
		valueView.text = value.value
	}

}