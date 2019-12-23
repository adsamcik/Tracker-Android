package com.adsamcik.tracker.preference

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.graphics.drawable.DrawableWrapper
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable

class StyleColorDrawable(val drawable: GradientDrawable) : DrawableWrapper(drawable),
		StyleableForegroundDrawable {
	override fun onForegroundStyleChanged(foregroundColor: ColorStateList) {
		drawable.setStroke(3.dp, foregroundColor)
	}
}

