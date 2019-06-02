package com.adsamcik.signalcollector.tracker.ui.recycler.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import com.adsamcik.signalcollector.tracker.ui.recycler.TrackerInfoAdapter

abstract class TrackerInfo(@StringRes val nameRes: Int, @DrawableRes val iconRes: Int) {
	fun bind(holder: TrackerInfoAdapter.ViewHolder) {
		holder.title.setText(nameRes)
		holder.title.apply {
			setText(nameRes)

			val icon = resources.getDrawable(iconRes, context.theme)
			setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
		}

		holder.content.removeAllViews()
		bindContent(holder)
	}

	protected fun setBoldText(holder: TrackerInfoAdapter.ViewHolder, callback: (title: AppCompatTextView, value: AppCompatTextView) -> Unit) {
		val context = holder.itemView.context
		val title = AppCompatTextView(context, null, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
		val value = AppCompatTextView(context, null, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1).apply {
			textSize = 20f
		}

		holder.content.let {
			it.addView(title)
			it.addView(value)
		}

		callback(title, value)
	}

	protected abstract fun bindContent(holder: TrackerInfoAdapter.ViewHolder)
}