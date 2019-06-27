package com.adsamcik.signalcollector.common.color

import androidx.annotation.ColorInt

data class ColorData(@ColorInt val baseColor: Int, @ColorInt val foregroundColor: Int, private val baseColorHSL: FloatArray, val perceivedLuminance: Byte) {
	val luminance get() = baseColorHSL[2]
	val saturation get() = baseColorHSL[1]
	val hue get() = baseColorHSL[0]

	/**
	 * Returns proper base foreground color for given ColorView
	 */
	@ColorInt
	fun foregroundColorFor(colorView: ColorView): Int = foregroundColorFor(colorView.isInverted)

	/**
	 * Returns proper base foreground color based on [backgroundIsForeground]
	 *
	 * @param backgroundIsForeground True if background and foreground should be inverted
	 */
	@ColorInt
	fun foregroundColorFor(backgroundIsForeground: Boolean): Int = if (backgroundIsForeground) ColorManager.currentColorData.baseColor else ColorManager.currentColorData.foregroundColor

	/**
	 * Returns proper base background color for given ColorView
	 */
	@ColorInt
	fun backgroundColorFor(colorView: ColorView): Int = backgroundColorFor(colorView.isInverted)

	/**
	 * Returns proper base background color based on [backgroundIsForeground]
	 *
	 * @param backgroundIsForeground True if background and foreground should be inverted
	 */
	@ColorInt
	fun backgroundColorFor(backgroundIsForeground: Boolean): Int = if (backgroundIsForeground) ColorManager.currentColorData.foregroundColor else ColorManager.currentColorData.baseColor

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as ColorData

		if (baseColor != other.baseColor) return false
		if (foregroundColor != other.foregroundColor) return false
		if (!baseColorHSL.contentEquals(other.baseColorHSL)) return false
		if (perceivedLuminance != other.perceivedLuminance) return false

		return true
	}

	override fun hashCode(): Int {
		var result = baseColor
		result = 31 * result + foregroundColor
		result = 31 * result + baseColorHSL.contentHashCode()
		result = 31 * result + perceivedLuminance
		return result
	}
}