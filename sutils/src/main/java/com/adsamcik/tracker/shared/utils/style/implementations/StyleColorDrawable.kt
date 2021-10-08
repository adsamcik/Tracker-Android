package com.adsamcik.tracker.shared.utils.style.implementations

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable

/**
 * Styleable color drawable
 */
class StyleColorDrawable(drawable: GradientDrawable, private val strokeWidth: Int) :
		StyleableDrawableWrapper(drawable) {


	override fun onForegroundStyleChanged(foregroundColor: ColorStateList) {
		drawable.setStroke(strokeWidth, foregroundColor)
	}

	companion object {
		const val BASE_STROKE_WIDTH: Int = 3
		const val EXTENDED_STROKE_WIDTH: Int = 4
	}
}

