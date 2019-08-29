package com.adsamcik.tracker.common.style

import android.R.attr.state_enabled
import android.R.attr.state_pressed
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
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.alpha
import androidx.core.view.children
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.tracker.common.Assist
import com.adsamcik.tracker.common.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.common.style.marker.StyleableView
import com.adsamcik.tracker.common.style.utility.ColorFunctions
import com.adsamcik.tracker.common.style.utility.brightenColor
import com.google.android.material.button.MaterialButton

internal class StyleUpdater {
	internal fun updateSingle(styleView: RecyclerStyleView, styleData: StyleData) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)

		styleView.view.post {
			updateSingle(styleView, backgroundColor, foregroundColor, perceivedLuminance)
		}
	}

	internal fun updateSingle(styleView: StyleView, styleData: StyleData) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val foregroundColor = styleData.foregroundColorFor(styleView)
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)

		styleView.view.post {
			updateSingle(
					backgroundColor, foregroundColor, perceivedLuminance, styleView.view,
					styleView.layer,
					styleView.maxDepth
			)
		}
	}


	@RequiresApi(Build.VERSION_CODES.M)
	internal fun updateNotificationBar(styleView: NotificationStyleView, styleData: StyleData) {
		val backgroundColor = styleData.backgroundColorFor(styleView)
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)

		styleView.view.post {
			styleView.view.systemUiVisibility = if (perceivedLuminance > 0) {
				styleView.view.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
			} else {
				styleView.view.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
			}

			styleView.window.statusBarColor = ColorFunctions.getBackgroundLayerColor(
					backgroundColor,
					perceivedLuminance,
					styleView.layer
			)
		}
	}

	@MainThread
	internal fun updateSingle(
			styleData: RecyclerStyleView,
			@ColorInt backgroundColor: Int,
			@ColorInt foregroundColor: Int,
			backgroundLuminance: Int
	) {
		if (!styleData.onlyChildren) {
			updateStyleForeground(styleData.view, foregroundColor)
			updateSingle(
					backgroundColor,
					foregroundColor,
					backgroundLuminance,
					styleData.view,
					styleData.layer,
					depthLeft = 0
			)
		}

		val iterator = styleData.view.children.iterator()

		for (item in iterator) {
			updateSingle(
					backgroundColor,
					foregroundColor,
					backgroundLuminance,
					item,
					styleData.childrenLayer,
					depthLeft = Int.MAX_VALUE
			)
		}
	}

	@MainThread
	@Suppress("LongParameterList")
	internal fun updateSingle(
			@ColorInt backgroundColor: Int,
			@ColorInt foregroundColor: Int,
			backgroundLuminance: Int,
			view: View,
			layer: Int,
			depthLeft: Int
	) {
		var newLayer = layer

		val backgroundLayerColor = ColorFunctions.getBackgroundLayerColor(
				backgroundColor,
				backgroundLuminance, layer
		)
		val wasBackgroundUpdated = updateBackgroundDrawable(
				view, backgroundLayerColor,
				backgroundLuminance
		)
		if (wasBackgroundUpdated) newLayer++

		if (view is ViewGroup) {
			if (depthLeft <= 0 || view is RecyclerView) return

			val newDepthLeft = depthLeft - 1

			for (i in 0 until view.childCount) {
				updateSingle(
						backgroundColor, foregroundColor, backgroundLuminance,
						view.getChildAt(i), newLayer,
						newDepthLeft
				)
			}
		} else {
			updateStyleForeground(view, foregroundColor)
		}
	}

	@MainThread
	private fun updateStyleForeground(drawable: Drawable, @ColorInt foregroundColor: Int) {
		when (drawable) {
			is StyleableForegroundDrawable -> drawable.onForegroundStyleChanged(foregroundColor)
			else -> drawable.setTint(foregroundColor)
		}
	}

	@MainThread
	private fun updateStyleForeground(view: TextView, @ColorInt foregroundColor: Int) {
		if (view is CheckBox) {
			view.buttonTintList = ColorStateList.valueOf(foregroundColor)
		}

		val alpha = view.currentTextColor.alpha
		val newTextColor = ColorUtils.setAlphaComponent(foregroundColor, alpha)
		view.setTextColor(newTextColor)
		view.setHintTextColor(
				brightenColor(
						newTextColor,
						ColorFunctions.LIGHTNESS_PER_LEVEL
				)
		)
		view.compoundDrawables.forEach {
			if (it != null) updateStyleForeground(it, foregroundColor)
		}
	}

	@MainThread
	private fun updateStyleForeground(view: SeekBar, @ColorInt foregroundColor: Int) {
		view.thumbTintList = ColorStateList(
				arrayOf(
						intArrayOf(-state_enabled),
						intArrayOf(state_enabled),
						intArrayOf(state_pressed)
				),
				intArrayOf(
						ColorUtils.setAlphaComponent(foregroundColor, SEEKBAR_DISABLED_ALPHA),
						foregroundColor,
						ColorUtils.setAlphaComponent(foregroundColor, SEEKBAR_PRESSED_ALPHA)
				)
		)
	}

	@MainThread
	private fun updateStyleForeground(view: ImageView, @ColorInt foregroundColor: Int) {
		view.drawable?.let { drawable ->
			updateStyleForeground(drawable, foregroundColor)
			view.invalidateDrawable(drawable)
		}
	}

	@MainThread
	private fun updateStyleForeground(view: RecyclerView, @ColorInt foregroundColor: Int) {
		for (i in 0 until view.itemDecorationCount) {
			when (val decoration = view.getItemDecorationAt(i)) {
				is DividerItemDecoration -> {
					val drawable = decoration.drawable
					if (drawable != null) updateStyleForeground(drawable, foregroundColor)
				}
			}
		}
	}

	@MainThread
	private fun updateStyleForeground(view: View, @ColorInt foregroundColor: Int) {
		when (view) {
			is StyleableView -> view.onStyleChanged(StyleManager.styleData)
			is ImageView -> updateStyleForeground(view, foregroundColor)
			is TextView -> updateStyleForeground(view, foregroundColor)
			is SeekBar -> updateStyleForeground(view, foregroundColor)
		}
	}

	//todo refactor
	@MainThread
	@Suppress("ReturnCount")
	private fun updateBackgroundDrawable(
			view: View,
			@ColorInt bgColor: Int,
			luminance: Int
	): Boolean {
		val background = view.background
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
					is ColorDrawable -> {
						view.setBackgroundColor(bgColor)
					}
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

	companion object {
		const val SEEKBAR_DISABLED_ALPHA = 128
		const val SEEKBAR_PRESSED_ALPHA = 255
	}
}
