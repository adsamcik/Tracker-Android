package com.adsamcik.signalcollector.common.recycler.decoration

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.adsamcik.recycler.card.CardListAdapter
import com.adsamcik.signalcollector.common.misc.extension.dp

/**
 * Implementation of [RecyclerView.ItemDecoration] for [CardListAdapter]. It will add uniform margin to all sides.
 */
class SimpleMarginDecoration(private val spaceBetweenItems: Int = 16.dp,
                             private val horizontalMargin: Int = 16.dp,
                             private val firstRowMargin: Int = 16.dp,
                             private val lastRowMargin: Int = 16.dp) : RecyclerView.ItemDecoration() {
	override fun getItemOffsets(outRect: Rect, view: View,
	                            parent: RecyclerView, state: RecyclerView.State) {

		with(outRect) {
			val columnCount = when (val layoutManager = parent.layoutManager) {
				is GridLayoutManager -> layoutManager.spanCount
				is LinearLayoutManager -> 1
				is StaggeredGridLayoutManager -> layoutManager.spanCount
				else -> 1
			}

			val position = parent.getChildAdapterPosition(view)
			top = if (position < columnCount) firstRowMargin
			else spaceBetweenItems

			val itemCount = parent.adapter?.itemCount ?: 0
			if (position >= itemCount - columnCount) bottom = lastRowMargin

			left = horizontalMargin
			right = horizontalMargin
		}
	}
}