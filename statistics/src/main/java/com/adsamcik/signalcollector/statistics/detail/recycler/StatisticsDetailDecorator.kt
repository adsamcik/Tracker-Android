package com.adsamcik.signalcollector.statistics.detail.recycler

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.signalcollector.common.misc.extension.dp

class StatisticsDetailDecorator(private val verticalMargin: Int = 16.dp, private val horizontalMargin: Int = 16.dp) : RecyclerView.ItemDecoration() {
	override fun getItemOffsets(outRect: Rect, view: View,
	                            parent: RecyclerView, state: RecyclerView.State) {

		with(outRect) {
			left = horizontalMargin
			right = horizontalMargin
			bottom = verticalMargin
		}
	}
}