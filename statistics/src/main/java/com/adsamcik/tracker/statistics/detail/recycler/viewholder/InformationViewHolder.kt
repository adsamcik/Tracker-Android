package com.adsamcik.tracker.statistics.detail.recycler.viewholder

import android.view.View
import com.adsamcik.tracker.common.recycler.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData
import kotlinx.android.synthetic.main.layout_stats_detail_item.view.*

class InformationViewHolder(view: View) : StyleMultiTypeViewHolder<InformationStatisticsData>(view) {
	private val iconView = view.icon
	private val titleView = view.title
	private val valueView = view.value

	override fun bind(data: InformationStatisticsData, styleController: StyleController) {
		val resources = itemView.resources
		iconView.setImageDrawable(resources.getDrawable(data.iconRes, itemView.context.theme))
		titleView.setText(data.titleRes)
		valueView.text = data.value
	}

}
