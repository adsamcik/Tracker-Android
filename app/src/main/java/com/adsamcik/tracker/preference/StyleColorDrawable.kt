package com.adsamcik.tracker.preference

import android.graphics.drawable.GradientDrawable
import androidx.appcompat.graphics.drawable.DrawableWrapper
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.style.marker.StyleableForegroundDrawable

class StyleColorDrawable(val drawable: GradientDrawable) : DrawableWrapper(drawable),
		StyleableForegroundDrawable {
	override fun onForegroundStyleChanged(foregroundColor: Int) {
		drawable.setStroke(3.dp, foregroundColor)
	}
}

