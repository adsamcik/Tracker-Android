package com.adsamcik.signalcollector.tracker.ui.recycler.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

abstract class TrackerInfo(@StringRes val nameRes: Int, @DrawableRes val iconRes: Int) {
	fun bind(holder: TrackerInfoAdapter.ViewHolder) {
		holder.title.setText(nameRes)
		holder.title.apply {
			setText(nameRes)

			val icon = resources.getDrawable(iconRes, context.theme)
			setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
		}
		bindContent(holder)
	}

	protected abstract fun bindContent(holder: TrackerInfoAdapter.ViewHolder)
}