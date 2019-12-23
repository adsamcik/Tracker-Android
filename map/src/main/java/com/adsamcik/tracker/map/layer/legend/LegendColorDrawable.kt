package com.adsamcik.tracker.map.layer.legend

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.graphics.drawable.DrawableWrapper
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable

class LegendColorDrawable(val drawable: GradientDrawable) : DrawableWrapper(drawable),
		StyleableForegroundDrawable {
	override fun onForegroundStyleChanged(foregroundColor: ColorStateList) {
		drawable.setStroke(4, foregroundColor)
	}
}
