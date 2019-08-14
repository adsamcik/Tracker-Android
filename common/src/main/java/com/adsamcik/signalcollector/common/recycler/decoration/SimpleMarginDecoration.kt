package com.adsamcik.signalcollector.common.recycler.decoration

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.adsamcik.recycler.card.CardListAdapter
import com.adsamcik.signalcollector.common.extension.dp

/**
 * Implementation of [RecyclerView.ItemDecoration] for [CardListAdapter]. It will add uniform margin to all sides.
 */
class SimpleMarginDecoration(private val verticalMargin: Int = 16.dp,
                             private val horizontalMargin: Int = 16.dp,
                             private val firstLineMargin: Int = 16.dp,
                             private val lastLineMargin: Int = 16.dp) : RecyclerView.ItemDecoration() {
	override fun getItemOffsets(outRect: Rect, view: View,
	                            parent: RecyclerView, state: RecyclerView.State) {

		with(outRect) {
			val columnCount: Int
			val orientation: Int
			when (val layoutManager = parent.layoutManager) {
				is GridLayoutManager -> {
					columnCount = layoutManager.spanCount
					orientation = layoutManager.orientation
				}
				is LinearLayoutManager -> {
					columnCount = 1
					orientation = layoutManager.orientation
				}
				is StaggeredGridLayoutManager -> {
					columnCount = layoutManager.spanCount
					orientation = layoutManager.orientation
				}
				else -> {
					columnCount = 1
					orientation = RecyclerView.VERTICAL
				}
			}

			val position = parent.getChildAdapterPosition(view)

			when (orientation) {
				RecyclerView.VERTICAL -> {
					top = if (position < columnCount) {
						firstLineMargin
					} else {
						verticalMargin
					}

					val itemCount = parent.adapter?.itemCount ?: 0
					if (position >= itemCount - columnCount) bottom = lastLineMargin

					left = horizontalMargin
					right = horizontalMargin
				}
				RecyclerView.HORIZONTAL -> {
					left = if (position < columnCount) {
						firstLineMargin
					} else {
						horizontalMargin
					}

					val itemCount = parent.adapter?.itemCount ?: 0
					if (position >= itemCount - columnCount) right = lastLineMargin

					top = verticalMargin
					bottom = verticalMargin
				}
				else -> throw IllegalStateException("Orientation with value $orientation is unknown")
			}
		}
	}
}