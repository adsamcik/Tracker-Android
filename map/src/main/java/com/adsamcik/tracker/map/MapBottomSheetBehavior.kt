package com.adsamcik.tracker.map

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class MapBottomSheetBehavior<V : View> : BottomSheetBehavior<V> {
	constructor() : super()
	constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

	private var isLockedInside = false
	private var direction = 0

	override fun onStartNestedScroll(
			coordinatorLayout: CoordinatorLayout,
			child: V,
			directTargetChild: View,
			target: View,
			axes: Int,
			type: Int
	): Boolean {
		isLockedInside = target.id == R.id.map_sheet_scroll_view && target.scrollY != 0 && state == STATE_EXPANDED

		if (isLockedInside) {
			return false
		}

		return super.onStartNestedScroll(
				coordinatorLayout,
				child,
				directTargetChild,
				target,
				axes,
				type
		)
	}

	override fun onNestedScroll(
			coordinatorLayout: CoordinatorLayout,
			child: V,
			target: View,
			dxConsumed: Int,
			dyConsumed: Int,
			dxUnconsumed: Int,
			dyUnconsumed: Int,
			type: Int,
			consumed: IntArray
	) {
		if (direction == 0) {
			direction = dyConsumed
		}
	}

	override fun onInterceptTouchEvent(
			parent: CoordinatorLayout,
			child: V,
			event: MotionEvent
	): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
			isLockedInside = false
			direction = 0
		}
		return !isLockedInside && direction <= 0 && super.onInterceptTouchEvent(
				parent,
				child,
				event
		)
	}
}
