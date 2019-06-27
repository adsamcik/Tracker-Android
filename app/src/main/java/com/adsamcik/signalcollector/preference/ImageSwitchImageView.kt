package com.adsamcik.signalcollector.preference

import android.R
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import com.adsamcik.signalcollector.common.color.ColorData
import com.adsamcik.signalcollector.common.color.ColorableView

class ImageSwitchImageView : AppCompatImageView, ColorableView {
	private var lastColor: Int = 0

	override fun onColorChanged(colorData: ColorData) {
		if (colorData.foregroundColor == lastColor) return

		val selectedColor = ColorUtils.setAlphaComponent(colorData.foregroundColor, SELECTED_ALPHA)
		val notSelectedColor = ColorUtils.setAlphaComponent(colorData.foregroundColor, NOT_SELECTED_ALPHA)

		imageTintList = ColorStateList(
				arrayOf(
						intArrayOf(-R.attr.state_selected),
						intArrayOf(R.attr.state_selected)
				),
				intArrayOf(
						notSelectedColor,
						selectedColor
				))
	}

	constructor(context: Context?) : super(context)
	constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
	constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

	companion object {
		const val SELECTED_ALPHA = 255
		const val NOT_SELECTED_ALPHA = 170
	}
}