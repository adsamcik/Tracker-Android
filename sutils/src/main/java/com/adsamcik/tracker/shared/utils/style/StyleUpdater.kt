package com.adsamcik.tracker.shared.utils.style

import android.R.attr.state_enabled
import android.R.attr.state_pressed
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.alpha
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.firstParent
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.shared.utils.style.marker.StyleableView
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

@Suppress("TooManyFunctions")
internal class StyleUpdater {
	internal fun updateSingle(
			styleView: RecyclerStyleView,
			styleData: StyleData,
			isAnimationAllowed: Boolean
	) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)

		val updateData = UpdateStyleData(
				backgroundColor,
				foregroundColor,
				perceivedLuminance,
				false,
				isAnimationAllowed
		)

		styleView.view.post {
			updateSingle(styleView, updateData)
		}
	}

	internal fun updateSingle(
			styleView: StyleView,
			styleData: StyleData,
			isAnimationAllowed: Boolean
	) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)

		val updateData = UpdateStyleData(
				backgroundColor,
				foregroundColor,
				perceivedLuminance,
				false,
				isAnimationAllowed
		)

		styleView.view.post {
			updateSingle(
					updateData,
					styleView.view,
					styleView.layer,
					styleView.maxDepth
			)
		}
	}

	@MainThread
	internal fun updateSingle(
			styleData: RecyclerStyleView,
			updateStyleData: UpdateStyleData
	) {
		if (!styleData.onlyChildren) {
			updateStyleForeground(styleData.view, updateStyleData)
			updateSingle(
					updateStyleData,
					styleData.view,
					styleData.layer,
					depthLeft = 0
			)
		}

		val iterator = styleData.view.children.iterator()

		for (item in iterator) {
			updateSingle(
					updateStyleData,
					item,
					styleData.childrenLayer,
					depthLeft = Int.MAX_VALUE
			)
		}
	}

	@MainThread
	@Suppress("LongParameterList")
	internal fun updateSingle(
			updateStyleData: UpdateStyleData,
			view: View,
			layer: Int,
			depthLeft: Int,
			allowRecycler: Boolean = false
	) {
		var newLayer = layer

		val backgroundLayerColor = ColorFunctions.getBackgroundLayerColor(
				updateStyleData.baseBackgroundColor,
				updateStyleData.backgroundLuminance,
				layer
		)
		val wasBackgroundUpdated = updateBackgroundDrawable(
				view,
				backgroundLayerColor,
				updateStyleData
		)
		if (wasBackgroundUpdated) newLayer++

		if (view is ViewGroup) {
			if (depthLeft <= 0 || (!allowRecycler && view is RecyclerView)) return

			val newDepthLeft = depthLeft - 1

			for (i in 0 until view.childCount) {
				updateSingle(
						updateStyleData,
						view.getChildAt(i),
						newLayer,
						newDepthLeft
				)
			}
		} else {
			updateStyleForeground(view, updateStyleData)
		}
	}

	@MainThread
	internal fun updateForegroundDrawable(drawable: Drawable, updateStyleData: UpdateStyleData) {
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
	private fun updateStyleForeground(view: CompoundButton, colorStateList: ColorStateList) {
		view.buttonTintList = colorStateList
	}

	@MainThread
	private fun updateStyleForeground(view: MaterialButton, colorStateList: ColorStateList) {
		view.iconTint = colorStateList
	}

	@MainThread
	private fun updateStyleForeground(view: AppCompatTextView, colorStateList: ColorStateList) {
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
	private fun updateStyleForeground(view: TextView, updateStyleData: UpdateStyleData) {
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		when (view) {
			is MaterialButton -> updateStyleForeground(view, colorStateList)
			is CompoundButton -> updateStyleForeground(view, colorStateList)
			is AppCompatTextView -> updateStyleForeground(view, colorStateList)
		}

		view.setTextColor(colorStateList)

		val hintColorState = colorStateList.withAlpha(alpha - HINT_TEXT_ALPHA_OFFSET)
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

	private fun updateStyleForeground(view: ProgressBar, updateStyleData: UpdateStyleData) {
		view.progressTintList = ColorStateList.valueOf(updateStyleData.baseForegroundColor)
		view.progressBackgroundTintList = ColorStateList.valueOf(
				updateStyleData.baseForegroundColor.withAlpha(DISABLED_ALPHA)
		)
	}

	@MainThread
	private fun updateStyleForeground(view: SeekBar, updateStyleData: UpdateStyleData) {
		updateStyleForeground(view as ProgressBar, updateStyleData)
		view.thumbTintList = ColorStateList(
				arrayOf(
						intArrayOf(-state_enabled),
						intArrayOf(state_enabled),
						intArrayOf(state_pressed)
				),
				intArrayOf(
						updateStyleData.baseForegroundColor.withAlpha(DISABLED_ALPHA),
						updateStyleData.baseForegroundColor,
						updateStyleData.baseForegroundColor.withAlpha(SEEKBAR_PRESSED_ALPHA)
				)
		)
	}

	@MainThread
	private fun updateStyleForeground(view: ImageView, updateStyleData: UpdateStyleData) {
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
	private fun updateStyleForeground(view: RecyclerView, updateStyleData: UpdateStyleData) {
		for (i in 0 until view.itemDecorationCount) {
			when (val decoration = view.getItemDecorationAt(i)) {
				is DividerItemDecoration -> {
					val drawable = decoration.drawable
					if (drawable != null) updateForegroundDrawable(drawable, updateStyleData)
				}
			}
		}
	}

	@MainThread
	private fun updateStyleForeground(view: View, updateStyleData: UpdateStyleData) {
		when (view) {
			is StyleableView -> view.onStyleChanged(StyleManager.styleData)
			is ImageView -> updateStyleForeground(view, updateStyleData)
			is TextView -> updateStyleForeground(view, updateStyleData)
			is SeekBar -> updateStyleForeground(view, updateStyleData)
			is ProgressBar -> updateStyleForeground(view, updateStyleData)
		}
	}

	private fun updateBackgroundColorDrawable(
			drawable: ColorDrawable,
			@ColorInt bgColor: Int,
			updateStyleData: UpdateStyleData
	) {
		val originalColor = drawable.color
		if (updateStyleData.isAnimationAllowed &&
				ColorFunctions.distance(originalColor, bgColor) > COLOR_DIST_ANIMATION_THRESHOLD) {
			val colorAnimation = ValueAnimator.ofObject(
					ArgbEvaluator(),
					originalColor,
					bgColor
			)
			colorAnimation.duration = 1000
			colorAnimation.addUpdateListener {
				drawable.color = it.animatedValue as Int
			}
			colorAnimation.start()
		} else {
			drawable.color = bgColor
		}
	}

	//todo refactor
	@MainThread
	@Suppress("ReturnCount")
	private fun updateBackgroundDrawable(
			view: View,
			@ColorInt bgColor: Int,
			updateStyleData: UpdateStyleData
	): Boolean {
		val background = view.background
		val luminance = updateStyleData.backgroundLuminance
		when {
			view is MaterialButton -> {
				val nextLevel = ColorFunctions.getBackgroundLayerColor(bgColor, luminance, 1)
				view.rippleColor = ColorStateList.valueOf(nextLevel)
				view.setBackgroundColor(bgColor)
			}
			background?.isVisible == true -> {
				if (background.alpha == 0) return false

				background.mutate()
				when (background) {
					is ColorDrawable -> updateBackgroundColorDrawable(
							background,
							bgColor,
							updateStyleData
					)
					is RippleDrawable -> {
						val nextLevel = ColorFunctions.getBackgroundLayerColor(
								bgColor,
								luminance,
								1
						)
						background.setColor(Assist.getPressedState(nextLevel))
						background.setTint(bgColor)
					}
					else -> {
						background.setTint(bgColor)
						background.colorFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							BlendModeColorFilter(bgColor, BlendMode.SRC_IN)
						} else {
							@Suppress("DEPRECATION")
							(PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_IN))
						}
					}
				}
				return true
			}
		}
		return false
	}

	data class UpdateStyleData(
			@ColorInt val baseBackgroundColor: Int,
			@ColorInt val baseForegroundColor: Int,
			val backgroundLuminance: Int,
			val isRecyclerAllowed: Boolean,
			val isAnimationAllowed: Boolean
	) {
		private val stateArray = arrayOf(
				intArrayOf(state_enabled),
				intArrayOf(-state_enabled)
		)

		fun getBaseTextColorStateList(alpha: Int = 255): ColorStateList {
			return ColorStateList(
					stateArray,
					intArrayOf(
							baseForegroundColor.withAlpha(alpha),
							baseForegroundColor.withAlpha(DISABLED_ALPHA)
					)
			)
		}
	}

	companion object {
		const val SEEKBAR_PRESSED_ALPHA = 255
		const val DISABLED_ALPHA = 97
		const val HINT_TEXT_ALPHA_OFFSET = 48

		private const val COLOR_DIST_ANIMATION_THRESHOLD = 50
	}
}
