package com.adsamcik.tracker.shared.utils.style

import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions
import com.adsamcik.tracker.shared.utils.style.utility.perceivedRelLuminance

/**
 * Style data object containing all the information about the current style
 */
data class StyleData(
		@ColorInt private val backgroundColor: Int,
		@ColorInt private val foregroundColor: Int
) {

	private val baseColorHSL: FloatArray = FloatArray(3)

	@IntRange(from = 0, to = 255)
	private val perceivedLuminance: Int

	val luminance get() = baseColorHSL[2]
	val saturation get() = baseColorHSL[1]
	val hue get() = baseColorHSL[0]


	init {
		ColorUtils.RGBToHSL(
				backgroundColor.red,
				backgroundColor.green,
				backgroundColor.blue,
				baseColorHSL
		)
		perceivedLuminance = perceivedRelLuminance(backgroundColor)
	}

	/**
	 * Returns proper base foreground color for given StyleView
	 */
	@ColorInt
	fun foregroundColorFor(styleView: BaseStyleView): Int = foregroundColor(styleView.isInverted)

	/**
	 * Returns proper base foreground color based on [isInverted]
	 *
	 * @param isInverted True if background and foreground should be inverted
	 */
	@ColorInt
	fun foregroundColor(isInverted: Boolean = false): Int =
			if (isInverted) backgroundColor else foregroundColor

	/**
	 * Returns proper base background color for given StyleView
	 */
	@ColorInt
	fun backgroundColorFor(styleView: BaseStyleView): Int = backgroundColor(styleView.isInverted)

	/**
	 * Returns proper base background color based on [isInverted]
	 *
	 * @param isInverted True if background and foreground should be inverted
	 */
	@ColorInt
	fun backgroundColor(isInverted: Boolean = false, layer: Int = 0): Int {
		val color: Int
		val luminance: Int

		if (isInverted) {
			color = foregroundColor
			luminance = -perceivedLuminance
		} else {
			color = backgroundColor
			luminance = perceivedLuminance
		}

		return ColorFunctions.getBackgroundLayerColor(color, luminance, layer)
	}

	internal fun updateDataFor(
			styleView: BaseStyleView,
			isRecyclerAllowed: Boolean = false,
			isAnimationAllowed: Boolean = true
	): StyleUpdater.UpdateStyleData {
		return StyleUpdater.UpdateStyleData(
				baseBackgroundColor = backgroundColorFor(styleView),
				baseForegroundColor = foregroundColorFor(styleView),
				backgroundLuminance = perceivedLuminanceFor(styleView),
				isRecyclerAllowed = isRecyclerAllowed,
				isAnimationAllowed = isAnimationAllowed
		)
	}

	/**
	 * Returns perceived luminance.
	 *
	 * @param isInverted Is background and foreground of the target inverted?
	 *
	 * @return Appropriate perceived luminance for the target
	 */
	fun perceivedLuminance(isInverted: Boolean = false): Int {
		return if (isInverted) 255 - perceivedLuminance else perceivedLuminance
	}

	/**
	 * Returns perceived luminance for the given [styleView]
	 *
	 * @param styleView Style view for which perceived luminance is returned
	 *
	 * @return Perceived luminance adjusted for the [styleView]
	 */
	fun perceivedLuminanceFor(styleView: BaseStyleView): Int =
			perceivedLuminance(styleView.isInverted)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as StyleData

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

