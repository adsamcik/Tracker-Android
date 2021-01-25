package com.adsamcik.tracker.statistics.detail.recycler.viewholder

import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.res.ResourcesCompat
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.shared.utils.style.StyleController
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.recycler.data.InformationStatisticsData

/**
 * Information view holder
 */
class InformationViewHolder(view: View) : StyleMultiTypeViewHolder<InformationStatisticsData>(view) {
	private val iconView = view.findViewById<AppCompatImageView>(R.id.icon)
	private val titleView = view.findViewById<AppCompatTextView>(R.id.title)
	private val valueView = view.findViewById<AppCompatTextView>(R.id.value)

	override fun bind(data: InformationStatisticsData, styleController: StyleController) {
		val resources = itemView.resources
		iconView.setImageDrawable(
				ResourcesCompat.getDrawable(
						resources,
						data.iconRes,
						itemView.context.theme
				)
		)
		titleView.setText(data.titleRes)
		valueView.text = data.value
	}

}
