package com.adsamcik.tracker.statistics.list.recycler

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import androidx.annotation.StringRes
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.marginRight
import com.google.android.material.button.MaterialButton
import io.github.luizgrp.sectionedrecyclerviewadapter.Section
import io.github.luizgrp.sectionedrecyclerviewadapter.SectionParameters
import kotlin.math.roundToInt

/**
 * Summary section for statistics recycler view
 */
class SummarySection : Section(
		SectionParameters.builder()
				.itemViewWillBeProvided()
				.build()
) {
	private val itemList = mutableListOf<ButtonData>()

	override fun getContentItemsTotal(): Int = 1

	/**
	 * Add new button to summary section.
	 */
	fun addButton(@StringRes text: Int, onClick: () -> Unit) {
		itemList.add(ButtonData(text, onClick))
	}

	private fun addButton(context: Context, buttonData: ButtonData): Button {
		return MaterialButton(context).apply {
			setPadding(BUTTON_PADDING.dp)
			setText(buttonData.text)
			setOnClickListener { buttonData.onClick.invoke() }
		}
	}

	override fun onBindItemViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
		holder as ViewHolder
		holder.layout.run {
			removeAllViews()
			itemList.forEach {
				val button = addButton(context, it)
				addView(button)
				button.marginRight = BUTTON_MARGIN_RIGHT.dp
			}
		}
	}

	override fun getItemViewHolder(view: View): RecyclerView.ViewHolder {
		return ViewHolder(view as ViewGroup, view.getChildAt(0) as ViewGroup)
	}

	override fun getItemView(parent: ViewGroup): View {
		return HorizontalScrollView(parent.context).apply {
			addView(
					LinearLayoutCompat(parent.context).apply {
						orientation = LinearLayoutCompat.HORIZONTAL
						layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
						val contentPadding = parent.resources.getDimension(
								com.adsamcik.tracker.shared.base.R.dimen.content_padding
						).roundToInt()
						setPadding(contentPadding, 0, contentPadding, 0)
					})
			isHorizontalScrollBarEnabled = false
		}
	}

	/**
	 * Holds important button data that differs for each button in [SummarySection].
	 */
	data class ButtonData(@StringRes val text: Int, val onClick: () -> Unit)
	private class ViewHolder(root: ViewGroup, val layout: ViewGroup) : RecyclerView.ViewHolder(root)

	companion object {
		private const val BUTTON_MARGIN_RIGHT = 16
		private const val BUTTON_PADDING = 16
	}
}

