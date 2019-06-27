package com.adsamcik.signalcollector.common.color

import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red

data class ColorData(@ColorInt private val backgroundColor: Int,
                     @ColorInt private val foregroundColor: Int) {

	private val baseColorHSL: FloatArray = FloatArray(3)
	val perceivedLuminance: Byte

	val luminance get() = baseColorHSL[2]
	val saturation get() = baseColorHSL[1]
	val hue get() = baseColorHSL[0]


	init {
		ColorUtils.RGBToHSL(backgroundColor.red, backgroundColor.green, backgroundColor.blue, baseColorHSL)
		perceivedLuminance = perceivedRelLuminance(backgroundColor)
	}

	/**
	 * Returns proper base foreground color for given ColorView
	 */
	@ColorInt
	fun foregroundColorFor(colorView: ColorView): Int = foregroundColor(colorView.isInverted)

	/**
	 * Returns proper base foreground color based on [isInverted]
	 *
	 * @param isInverted True if background and foreground should be inverted
	 */
	@ColorInt
	fun foregroundColor(isInverted: Boolean): Int = if (isInverted) backgroundColor else foregroundColor

	/**
	 * Returns proper base background color for given ColorView
	 */
	@ColorInt
	fun backgroundColorFor(colorView: ColorView): Int = backgroundColor(colorView.isInverted)

	/**
	 * Returns proper base background color based on [isInverted]
	 *
	 * @param isInverted True if background and foreground should be inverted
	 */
	@ColorInt
	fun backgroundColor(isInverted: Boolean): Int = if (isInverted) foregroundColor else backgroundColor

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ColorData

		if (backgroundColor != other.backgroundColor) return false
		if (foregroundColor != other.foregroundColor) return false
		if (!baseColorHSL.contentEquals(other.baseColorHSL)) return false
		if (perceivedLuminance != other.perceivedLuminance) return false

		return true
	}

	override fun hashCode(): Int {
		var result = backgroundColor
		result = 31 * result + foregroundColor
		result = 31 * result + baseColorHSL.contentHashCode()
		result = 31 * result + perceivedLuminance
		return result
	}
}