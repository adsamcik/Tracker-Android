package com.adsamcik.tracker.tracker.ui.recycler.data

import android.content.Context
import android.content.res.Resources
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.adsamcik.tracker.shared.base.extension.detach
import com.adsamcik.tracker.tracker.ui.recycler.TrackerInfoAdapter

abstract class TrackerInfo(@StringRes val nameRes: Int) {

	/**
	 * Resource for icon that is used in title
	 */
	abstract val iconRes: Int

	//Todo make lastIndex safe
	fun bind(holder: TrackerInfoAdapter.ViewHolder) {
		val infoFieldHolder = InfoFieldHolder(holder)

		bindTitle(holder.title)
		bindContent(infoFieldHolder)
		infoFieldHolder.done()
	}

	protected open fun bindTitle(title: TextView) {
		title.apply {
			setText(nameRes)

			val icon = ResourcesCompat.getDrawable(resources, iconRes, context.theme)
			setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
		}
	}

	protected abstract fun bindContent(holder: InfoFieldHolder)

	protected class InfoFieldHolder(private val holder: TrackerInfoAdapter.ViewHolder) {
		private var lastIndex = -1

		val context: Context = holder.itemView.context
		val resources: Resources = holder.itemView.resources

		fun getBoldText(): TrackerInfoAdapter.InfoField {
			++lastIndex
			return if (holder.fields.size > lastIndex) {
				holder.fields[lastIndex]
			} else {
				val context = holder.itemView.context
				val title = AppCompatTextView(
						context, null,
						com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption
				)
				val value = AppCompatTextView(
						context, null,
						com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1
				).apply {
					textSize = 20f
				}

				holder.content.let {
					it.addView(title)
					it.addView(value)
				}

				return TrackerInfoAdapter.InfoField(title, value).also { holder.fields.add(it) }
			}
		}

		fun done() {
			for (i in holder.fields.size - 1 downTo lastIndex + 1) {
				holder.fields[i].let {
					it.title.detach()
					it.value.detach()
				}
				holder.fields.removeAt(i)
			}
		}
	}
}

