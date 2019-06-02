package com.adsamcik.signalcollector.common.recycler

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
class SimpleMarginDecoration(private val verticalMargin: Int = 16.dp, private val horizontalMargin: Int = 16.dp) : RecyclerView.ItemDecoration() {
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
			if (position < columnCount) top = verticalMargin

			left = horizontalMargin
			right = horizontalMargin
			bottom = verticalMargin
		}
	}
}