package com.adsamcik.tracker.shared.utils.style.update.system

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.slider.abstracts.FluidSlider
import com.adsamcik.tracker.shared.base.extension.firstParent
import com.adsamcik.tracker.shared.base.extension.toTintList
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.shared.utils.style.marker.StyleableView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

@Suppress("unused_parameter")
internal class ComponentStyleUpdater {
	companion object {
		private const val SWITCH_PRESSED_ALPHA = 255
		private const val SWITCH_OFF_BLEND = 0.35f

	}

	private val edgeEffectFactory = RecyclerEdgeEffectFactory()

	@MainThread
	private fun updateForegroundDrawable(
			drawable: Drawable,
			updateStyleData: StyleUpdater.UpdateStyleData
	) {
		drawable.mutate()
		when (drawable) {
			is StyleableForegroundDrawable -> drawable.onForegroundStyleChanged(
					updateStyleData.getBaseTextColorStateList(
							255
					)
			)
			else -> DrawableCompat.setTint(drawable, updateStyleData.baseForegroundColor)
		}
	}

	@MainThread
	private fun updateStyle(
			view: CompoundButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		view.buttonTintList = colorStateList
	}

	@MainThread
	private fun updateStyle(
			view: FluidSlider,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val foreground = updateStyleData.baseForegroundColor.withAlpha(ColorConstants.FULL_COMPONENT)
		val background = updateStyleData.baseBackgroundColor
		view.colorBarText = background
		view.colorBubbleText = foreground
		view.descriptionPaint.color = updateStyleData.baseForegroundColor.withAlpha(ColorConstants.MEDIUM_EMPHASIS_ALPHA)
		view.colorBubble = background
		view.colorBar = foreground
	}

	@MainThread
	private fun updateStyle(
			view: AppCompatTextView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		updateStyle(view as TextView, updateStyleData, backgroundColor)
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		var isAnyStyleable = false
		view.compoundDrawables.forEach {
			if (it is StyleableForegroundDrawable) {
				it.onForegroundStyleChanged(colorStateList)
				isAnyStyleable = true
			}
		}

		if (!isAnyStyleable) {
			TextViewCompat.setCompoundDrawableTintList(view, colorStateList)
			//view.supportCompoundDrawablesTintMode = PorterDuff.Mode.SRC_ATOP
		}

	}

	@MainThread
	private fun updateStyle(
			view: TextView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		view.setTextColor(colorStateList)

		val hintColorState = colorStateList.withAlpha(alpha - StyleUpdater.HINT_TEXT_ALPHA_OFFSET)
		if (view is TextInputEditText) {
			val parent = view.firstParent<TextInputLayout>(1)
			require(parent is TextInputLayout) {
				"TextInputEditText ($view) should always have TextInputLayout as it's parent! Found $parent instead"
			}

			parent.defaultHintTextColor = hintColorState
		} else {
			view.setHintTextColor(hintColorState)
		}
	}

	private fun updateStyle(
			view: ProgressBar,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		view.progressTintList = ColorStateList.valueOf(updateStyleData.baseForegroundColor)
		view.progressBackgroundTintList = ColorStateList.valueOf(
				updateStyleData.baseForegroundColor.withAlpha(StyleUpdater.DISABLED_ALPHA)
		)
	}

	@MainThread
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

	@MainThread
	private fun updateStyle(
			view: ImageView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val drawable = view.drawable
		if (drawable is StyleableForegroundDrawable) {
			val drawableAlpha = drawable.alpha
			val colorStateList = updateStyleData.getBaseTextColorStateList(drawableAlpha)
			drawable.onForegroundStyleChanged(colorStateList)
		} else {
			view.imageTintList = ColorStateList.valueOf(updateStyleData.baseForegroundColor)
		}
	}

	@MainThread
	fun updateStyle(
			view: RecyclerView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		edgeEffectFactory.color = updateStyleData.baseForegroundColor

		view.edgeEffectFactory = edgeEffectFactory
		for (i in 0 until view.itemDecorationCount) {
			when (val decoration = view.getItemDecorationAt(i)) {
				is DividerItemDecoration -> {
					decoration.drawable?.let { drawable ->
						updateForegroundDrawable(drawable, updateStyleData)
					}
				}
			}
		}
	}

	@MainThread
	private fun updateStyle(
			view: FloatingActionButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val nextLevel = ColorFunctions.getBackgroundLayerColor(
				backgroundColor,
				updateStyleData.backgroundLuminance,
				1
		)
		view.rippleColor = nextLevel
		//view.setBackgroundColor(backgroundColor)
		view.backgroundTintList = backgroundColor.toTintList()
	}

	@MainThread
	private fun updateStyle(
			view: MaterialButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		updateStyle(view as TextView, updateStyleData, backgroundColor)

		val nextLevel = ColorFunctions.getBackgroundLayerColor(
				backgroundColor,
				updateStyleData.backgroundLuminance,
				1
		)
		view.rippleColor = nextLevel.toTintList()
		view.setBackgroundColor(backgroundColor)
		val alpha = view.textColors.defaultColor.alpha
		view.iconTint = updateStyleData.getBaseTextColorStateList(alpha)
	}


	@Suppress("ComplexMethod")
	@MainThread
	fun updateStyle(
			view: View,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		when (view) {
			is StyleableView -> view.onStyleChanged(StyleManager.styleData)
			is MaterialButton -> updateStyle(view, updateStyleData, backgroundColor)
			is FloatingActionButton -> updateStyle(view, updateStyleData, backgroundColor)
			is FluidSlider -> updateStyle(view, updateStyleData, backgroundColor)
			is SwitchCompat -> updateStyle(view, updateStyleData, backgroundColor)
			is ProgressBar -> updateStyle(view, updateStyleData, backgroundColor)
			is CompoundButton -> updateStyle(view, updateStyleData, backgroundColor)
			is ImageView -> updateStyle(view, updateStyleData, backgroundColor)
			is AppCompatTextView -> updateStyle(view, updateStyleData, backgroundColor)
			is TextView -> updateStyle(view, updateStyleData, backgroundColor)
		}
	}
}
