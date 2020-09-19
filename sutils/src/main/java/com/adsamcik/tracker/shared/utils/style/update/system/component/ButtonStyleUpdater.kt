package com.adsamcik.tracker.shared.utils.style.update.system.component

import android.content.res.ColorStateList
import android.widget.Button
import android.widget.CompoundButton
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import com.adsamcik.tracker.shared.base.extension.toTintList
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions
import com.adsamcik.tracker.shared.utils.style.update.system.StyleUpdater
import com.google.android.material.button.MaterialButton

@Suppress("unused_parameter")
@MainThread
internal class ButtonStyleUpdater {
	companion object {
		private const val SWITCH_PRESSED_ALPHA = 255
		private const val SWITCH_OFF_BLEND = 0.35f
	}

	//region Button
	fun updateStyle(
			view: Button,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		when (view) {
			is AppCompatButton -> updateStyle(view, updateStyleData, backgroundColor)
			is CompoundButton -> updateStyle(view, updateStyleData, backgroundColor)
		}
	}
	//endregion

	//region AppCompatButton
	private fun updateStyle(
			view: AppCompatButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		view.setBackgroundColor(backgroundColor)

		when (view) {
			is MaterialButton -> updateStyle(view, updateStyleData, backgroundColor)
		}
	}

	private fun updateStyle(
			view: MaterialButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val nextLevel = ColorFunctions.getBackgroundLayerColor(
				backgroundColor,
				updateStyleData.backgroundLuminance,
				1
		)
		view.rippleColor = nextLevel.toTintList()
		val alpha = view.textColors.defaultColor.alpha
		view.iconTint = updateStyleData.getBaseTextColorStateList(alpha)
	}
	//endregion

	//region CompoundButton
	private fun updateStyle(
			view: CompoundButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		view.buttonTintList = colorStateList

		when (view) {
			is SwitchCompat -> updateStyle(view, updateStyleData, backgroundColor)
		}
	}


	private fun updateStyle(
			view: SwitchCompat,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val luminance = updateStyleData.backgroundLuminance
		val nextLevel = ColorFunctions.getBackgroundLayerColor(backgroundColor, luminance, 1)
		view.thumbTintList = ColorStateList(
				arrayOf(
						intArrayOf(-android.R.attr.state_enabled),
						intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
						intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
						intArrayOf(android.R.attr.state_pressed),
				),
				intArrayOf(
						updateStyleData.baseForegroundColor.withAlpha(StyleUpdater.DISABLED_ALPHA),
						ColorUtils.blendARGB(
								nextLevel,
								updateStyleData.baseForegroundColor,
								SWITCH_OFF_BLEND
						),
						updateStyleData.baseForegroundColor,
						updateStyleData.baseForegroundColor.withAlpha(SWITCH_PRESSED_ALPHA),
				)
		)

		view.trackTintList = nextLevel.toTintList()
	}
	//endregion
}
