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
class SimpleMarginDecoration(
		private val verticalMargin: Int = DEFAULT_MARGIN_DP.dp,
		private val horizontalMargin: Int = DEFAULT_MARGIN_DP.dp,
		private val firstLineMargin: Int = DEFAULT_MARGIN_DP.dp,
		private val lastLineMargin: Int = DEFAULT_MARGIN_DP.dp
) : RecyclerView.ItemDecoration() {


	private fun setOffsetsHorizontal(
			outRect: Rect,
			parent: RecyclerView,
			position: Int,
			columnCount: Int
	) {
		with(outRect) {
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
	}

	private fun setOffsetsVertical(
			outRect: Rect,
			parent: RecyclerView,
			position: Int,
			columnCount: Int
	) {
		with(outRect) {
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
	}

	override fun getItemOffsets(
			outRect: Rect,
			view: View,
			parent: RecyclerView,
			state: RecyclerView.State
	) {

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
			RecyclerView.VERTICAL -> setOffsetsVertical(outRect, parent, position, columnCount)
			RecyclerView.HORIZONTAL -> setOffsetsHorizontal(outRect, parent, position, columnCount)
			else -> throw IllegalStateException("Orientation with value $orientation is unknown")
		}
	}

	companion object {
		const val DEFAULT_MARGIN_DP = 16
	}
}
