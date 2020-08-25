package com.adsamcik.tracker.shared.utils.style.update.system

import android.R.attr.state_enabled
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.extension.runOnUiThread
import com.adsamcik.tracker.shared.utils.style.RecyclerStyleView
import com.adsamcik.tracker.shared.utils.style.StyleData
import com.adsamcik.tracker.shared.utils.style.StyleView
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions
import com.google.android.material.floatingactionbutton.FloatingActionButton

@AnyThread
internal class StyleUpdater {
	private val componentUpdater = ComponentStyleUpdater()


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
			updateSingle(styleView, updateData, backgroundColor)
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

		styleView.view.runOnUiThread {
			@Suppress("WrongThread")
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
			updateStyleData: UpdateStyleData,
			@ColorInt backgroundColor: Int,
	) {
		if (!styleData.onlyChildren) {
			componentUpdater.updateStyle(styleData.view, updateStyleData, backgroundColor)
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
			componentUpdater.updateStyle(view, updateStyleData, backgroundLayerColor)
		}
	}


	@MainThread
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
			view is FloatingActionButton -> {

			}
			view is SwitchCompat -> {

			}
			background != null -> {
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
