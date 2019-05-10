package com.adsamcik.signalcollector.common.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.recycler.card.CardListAdapter
import com.adsamcik.signalcollector.common.misc.extension.dp

/**
 * Implementation of [RecyclerView.ItemDecoration] for [CardListAdapter]. It will add uniform margin to all sides.
 */
class SimpleMarginDecoration(private val verticalMargin: Int = 16.dp, private val horizontalMargin: Int = 16.dp) : RecyclerView.ItemDecoration() {
	override fun getItemOffsets(outRect: Rect, view: View,
	                            parent: RecyclerView, state: RecyclerView.State) {

		with(outRect) {
			if (parent.getChildAdapterPosition(view) == 0) top = verticalMargin
			left = horizontalMargin
			right = horizontalMargin
			bottom = verticalMargin
		}
	}
}