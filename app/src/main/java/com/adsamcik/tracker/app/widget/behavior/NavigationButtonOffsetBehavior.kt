package com.adsamcik.tracker.app.widget.behavior

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.guidelineEnd
import kotlin.math.roundToInt

/**
 * Custom behavior that allows specific view to be modified instead of direct child of coordinator layout
 */
class NavigationButtonOffsetBehavior(private val targetView: View) : CoordinatorLayout.Behavior<ConstraintLayout>() {

	private val dp16 = 16.dp

	/**
	 * Used to determine whether the target view moved
	 */
	private var last: Float = Float.MIN_VALUE

	private var layoutChange = 0f
	private var ignore = false

	override fun layoutDependsOn(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View): Boolean {
		return dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout
	}

	override fun onDependentViewChanged(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View): Boolean {
		var changed = false
		if (dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout) {
			if (last == Float.MIN_VALUE) {
				ignore = false
				last = targetView.translationY
			}

			if (last != targetView.translationY) {
				ignore = false
				layoutChange = 0.0f
				return false
			}

			val diff = dependency.y - (targetView.y + targetView.height + dp16)

			if (diff < 0) {
				layoutChange += diff
				targetView.translationY += diff
				changed = true
			} else if (layoutChange < 0) {
				if (diff > layoutChange) {
					targetView.translationY -= layoutChange
					layoutChange = 0f
				} else {
					layoutChange += diff
					targetView.translationY += diff
				}
				changed = true
			}

			last = targetView.translationY
		}
		return changed
	}

	override fun onDependentViewRemoved(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View) {
		//Ensures that the view does not get stuck in out of the way position
		targetView.translationY += layoutChange
		last = Float.MIN_VALUE
		super.onDependentViewRemoved(parent, child, dependency)
	}
}

/**
 * Custom behavior that allows specific view to be modified instead of direct child of coordinator layout
 */
class NavigationGuidelinesOffsetBehavior(private val guideline: Guideline) : CoordinatorLayout.Behavior<ConstraintLayout>() {
	private val dp16 = 16.dp

	/**
	 * Used to determine whether the target view moved
	 */
	private var last: Int = Int.MIN_VALUE
	private var offset: Float = 0f

	private var layoutChange = 0f

	override fun layoutDependsOn(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View): Boolean {
		return dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout
	}

	init {
		reset(guideline.guidelineEnd)
	}


	private fun reset(current: Int) {
		last = current
		layoutChange = 0.0f
		offset = current.toFloat()
	}

	override fun onDependentViewChanged(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View): Boolean {
		var changed = false
		if (dependency is com.google.android.material.snackbar.Snackbar.SnackbarLayout) {
			val current = guideline.guidelineEnd
			if (last == Int.MIN_VALUE) {
				reset(current)
			} else if (last != current) {
				reset(current)
				return false
			}

			val diff = dependency.y - child.y

			if (diff < 0) {
				offset -= diff
				layoutChange += diff

				changed = true
				guideline.guidelineEnd = offset.roundToInt()
			} else if (layoutChange < 0) {
				if (diff > layoutChange) {
					offset += layoutChange
					layoutChange = 0f
				} else {
					offset -= diff
					layoutChange += diff
				}
				guideline.guidelineEnd = offset.roundToInt()
				changed = true
			}

			last = current
		}
		return changed
	}

	override fun onDependentViewRemoved(parent: CoordinatorLayout, child: ConstraintLayout, dependency: View) {
		//Ensures that the view does not get stuck in out of the way position
		guideline.translationY += layoutChange
		last = Int.MIN_VALUE
		super.onDependentViewRemoved(parent, child, dependency)
	}
}
