package com.adsamcik.signalcollector.common.extension

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.widget.LinearLayoutCompat
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
		is CoordinatorLayout.LayoutParams -> layoutParams.setMargins(left, top, right, bottom)
	}
	requestLayout()
}

/**
 * Converts density independent pixels to pixels. Round to whole pixels.
 */
val Int.dp: Int get() = (this * Resources.getSystem().displayMetrics.density).toInt()

private class ViewGroupNotSupportedException(message: String? = null) : Exception(message)

/**
 * Left margin of this View. Relies on non-null layout params to work properly.
 *
 * Supports margins of [ConstraintLayout], [LinearLayout], [RelativeLayout], [CoordinatorLayout]
 */
var View.marginLeft: Int
	get() {
		return when (val layoutParams = layoutParams ?: return 0) {
			is ConstraintLayout.LayoutParams -> layoutParams.leftMargin
			is LinearLayoutCompat.LayoutParams -> layoutParams.leftMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.leftMargin
			else -> throw ViewGroupNotSupportedException()
		}
	}
	set(margin) {
		when (val layoutParams = layoutParams ?: return) {
			is ConstraintLayout.LayoutParams -> layoutParams.leftMargin = margin
			is LinearLayoutCompat.LayoutParams -> layoutParams.leftMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.leftMargin = margin
			else -> throw ViewGroupNotSupportedException()
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
		return when (val layoutParams = layoutParams ?: return 0) {
			is ConstraintLayout.LayoutParams -> layoutParams.topMargin
			is LinearLayoutCompat.LayoutParams -> layoutParams.topMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.topMargin
			else -> throw ViewGroupNotSupportedException()
		}
	}
	set(margin) {
		when (val layoutParams = layoutParams ?: return) {
			is ConstraintLayout.LayoutParams -> layoutParams.topMargin = margin
			is LinearLayoutCompat.LayoutParams -> layoutParams.topMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.topMargin = margin
			else -> throw ViewGroupNotSupportedException()
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
		return when (val layoutParams = layoutParams ?: return 0) {
			is ConstraintLayout.LayoutParams -> layoutParams.rightMargin
			is LinearLayoutCompat.LayoutParams -> layoutParams.rightMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.rightMargin
			else -> throw ViewGroupNotSupportedException()
		}
	}
	set(margin) {
		when (val layoutParams = layoutParams ?: return) {
			is ConstraintLayout.LayoutParams -> layoutParams.rightMargin = margin
			is LinearLayoutCompat.LayoutParams -> layoutParams.rightMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.rightMargin = margin
			else -> throw ViewGroupNotSupportedException()
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
		return when (val layoutParams = layoutParams ?: return 0) {
			is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin
			is LinearLayoutCompat.LayoutParams -> layoutParams.bottomMargin
			is CoordinatorLayout.LayoutParams -> layoutParams.bottomMargin
			else -> throw ViewGroupNotSupportedException("Layout params")
		}
	}
	set(margin) {
		when (val layoutParams = layoutParams ?: return) {
			is ConstraintLayout.LayoutParams -> layoutParams.bottomMargin = margin
			is LinearLayoutCompat.LayoutParams -> layoutParams.bottomMargin = margin
			is CoordinatorLayout.LayoutParams -> layoutParams.bottomMargin = margin
			else -> throw ViewGroupNotSupportedException()
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
	if (this == childView) return true

	return if (this is ViewGroup) {
		children.any { it.contains(childView) }
	} else {
		false
	}
}

var Guideline.guidelineEnd: Int
	get() = (layoutParams as ConstraintLayout.LayoutParams).guideEnd
	set(value) = setGuidelineEnd(value)

fun View.detach() {
	val parent = parent as? ViewGroup
	parent?.removeView(this)
}

inline fun <reified T> View.firstParent(): T? {
	return firstParent(T::class.java)
}

fun <T> View.firstParent(iClass: Class<T>): T? {
	var parent = parent
	while (parent != null) {
		if (iClass.isInstance(parent)) {
			@Suppress("unchecked_cast")
			return parent as T
		}

		parent = parent.parent
	}
	return null
}