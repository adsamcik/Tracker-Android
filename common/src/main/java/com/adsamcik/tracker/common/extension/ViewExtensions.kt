package com.adsamcik.tracker.common.extension

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.children
import com.adsamcik.tracker.common.exception.NotFoundException
import com.adsamcik.tracker.common.style.BaseStyleView
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.StyleManager
import java.util.*

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

inline fun <reified T> View.firstParent(maxDistance: Int = Int.MAX_VALUE): T? {
	return firstParent(T::class.java, maxDistance)
}

inline fun <reified T : Any> View.requireParent(maxDistance: Int = Int.MAX_VALUE): T {
	return requireNotNull(firstParent(T::class.java, maxDistance))
}


fun <T> View.firstParent(iClass: Class<T>, maxDistance: Int = Int.MAX_VALUE): T? {
	var parent = parent
	var distance = 0
	while (parent != null && maxDistance >= distance) {
		if (iClass.isInstance(parent)) {
			@Suppress("unchecked_cast")
			return parent as T
		}

		parent = parent.parent
		distance++
	}
	return null
}

inline fun <reified T : View> ViewGroup.findChildrenOfType() =
		findChildrenOfType(T::class.java)

fun <T : View> ViewGroup.findChildrenOfType(
		iClass: Class<T>
): Collection<T> {
	val found = mutableListOf<T>()
	val queue = ArrayDeque<ViewGroup>()

	queue.push(this)

	while (true) {
		val item = queue.poll() ?: break

		item.children.forEach {
			if (it is ViewGroup) queue.add(it)

			@Suppress("unchecked_cast")
			if (iClass.isInstance(it)) found.add(it as T)
		}
	}

	assert(queue.isEmpty())
	return found
}

inline fun <reified T : View> ViewGroup.findChildOfType() =
		findChildOfType(T::class.java)

fun <T : View> ViewGroup.findChildOfType(
		iClass: Class<T>
): T {
	val queue = ArrayDeque<ViewGroup>()

	queue.push(this)

	while (true) {
		val item = queue.poll() ?: break

		item.children.forEach {
			if (it is ViewGroup) queue.add(it)

			@Suppress("unchecked_cast")
			if (iClass.isInstance(it)) return it as T
		}
	}

	assert(queue.isEmpty())
	throw NotFoundException("Child of type ${iClass.name} not found")
}

fun ImageView.replaceDrawable(drawable: Drawable) {
	/*val currentDrawable: Drawable? = this.drawable
	drawable.mutate()
	if(currentDrawable != null) {
		drawable.setTint(imageTintList)
	}*/
}
