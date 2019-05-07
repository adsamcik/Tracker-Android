package com.adsamcik.signalcollector.common.misc.extension

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.children

/**
 * Sets all margins. Does not work with null layout params.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
fun View.setMargin(left: Int, top: Int, right: Int, bottom: Int) {
	when (val layoutParams = layoutParams ?: return) {
		is ConstraintLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
		is LinearLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
		is RelativeLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
		is CoordinatorLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
	}
	requestLayout()
}

/**
 * Converts pixels to density independent pixels
 */
val Int.pxAsDp: Float get() = this / Resources.getSystem().displayMetrics.density

/**
 * Converts density independent pixels to pixels. Round to whole pixels.
 */
val Int.dpAsPx: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

/**
 * Left margin of this View. Relies on non-null layout params to work properly.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
var View.marginLeft: Int
	get() {
		val layoutParams = layoutParams ?: return 0
		return when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.leftMargin
			is LinearLayout.LayoutParams -> layoutParams.leftMargin
			is RelativeLayout.LayoutParams -> layoutParams.leftMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.leftMargin
			else -> 0
		}
	}
	set(margin) {
		val layoutParams = layoutParams ?: return
		when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.leftMargin = margin
			is LinearLayout.LayoutParams -> layoutParams.leftMargin = margin
			is RelativeLayout.LayoutParams -> layoutParams.leftMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.leftMargin = margin
			else -> return
		}
		requestLayout()
	}

/**
 * Top margin of this View. Relies on non-null layout params to work properly.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
var View.marginTop: Int
	get() {
		val layoutParams = layoutParams ?: return 0
		return when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.topMargin
			is LinearLayout.LayoutParams -> layoutParams.topMargin
			is RelativeLayout.LayoutParams -> layoutParams.topMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.topMargin
			else -> 0
		}
	}
	set(margin) {
		val layoutParams = layoutParams ?: return
		when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.topMargin = margin
			is LinearLayout.LayoutParams -> layoutParams.topMargin = margin
			is RelativeLayout.LayoutParams -> layoutParams.topMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.topMargin = margin
			else -> return
		}
		requestLayout()
	}

/**
 * Right margin of this View. Relies on non-null layout params to work properly.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
var View.marginRight: Int
	get() {
		val layoutParams = layoutParams ?: return 0
		return when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.rightMargin
			is LinearLayout.LayoutParams -> layoutParams.rightMargin
			is RelativeLayout.LayoutParams -> layoutParams.rightMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.rightMargin
			else -> 0
		}
	}
	set(margin) {
		val layoutParams = layoutParams ?: return
		when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.rightMargin = margin
			is LinearLayout.LayoutParams -> layoutParams.rightMargin = margin
			is RelativeLayout.LayoutParams -> layoutParams.rightMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.rightMargin = margin
			else -> return
		}
		requestLayout()
	}

/**
 * Bottom margin of this View. Relies on non-null layout params to work properly.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
var View.marginBottom: Int
	get() {
		val layoutParams = layoutParams ?: return 0
		return when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin
			is LinearLayout.LayoutParams -> layoutParams.bottomMargin
			is RelativeLayout.LayoutParams -> layoutParams.bottomMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.bottomMargin
			else -> 0
		}
	}
	set(margin) {
		val layoutParams = layoutParams ?: return
		when (layoutParams) {
			is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin = margin
			is LinearLayout.LayoutParams -> layoutParams.bottomMargin = margin
			is RelativeLayout.LayoutParams -> layoutParams.bottomMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.bottomMargin = margin
			else -> return
		}
		requestLayout()
	}

/**
 * Checks if child View is descendant of this View or is this View.
 *
 * @param childView Child View which is searched for recursively
 * @return True if child View is equal to this view or child View is descendant of this View
 */
fun View.contains(childView: View): Boolean {
	if (this == childView)
		return true

	return if (this is ViewGroup)
		children.any { it.contains(childView) }
	else
		false
}

var Guideline.guidelineEnd: Int
	get() = (layoutParams as ConstraintLayout.LayoutParams).guideEnd
	set(value) = setGuidelineEnd(value)