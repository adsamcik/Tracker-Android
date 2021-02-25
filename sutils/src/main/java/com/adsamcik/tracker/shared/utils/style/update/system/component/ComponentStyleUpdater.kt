package com.adsamcik.tracker.shared.utils.style.update.system.component

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.adsamcik.slider.abstracts.FluidSlider
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.StyleManager
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.shared.utils.style.marker.StyleableView
import com.adsamcik.tracker.shared.utils.style.update.system.RecyclerEdgeEffectFactory
import com.adsamcik.tracker.shared.utils.style.update.system.StyleUpdater
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Component style updater.
 */
@Suppress("unused_parameter")
@MainThread
internal class ComponentStyleUpdater {
	private val edgeEffectFactory = RecyclerEdgeEffectFactory()
	private val imageViewStyleUpdater = ImageViewStyleUpdater()
	private val textViewStyleUpdater = TextViewStyleUpdater()

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

	//region FluidSlider
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
	//endregion

	//region ProgressBar
	private fun updateStyle(
			view: ProgressBar,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val foregroundTintList = ColorStateList.valueOf(updateStyleData.baseForegroundColor)
		view.progressTintList = foregroundTintList
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			view.foregroundTintList = foregroundTintList
		}
		view.indeterminateTintList = foregroundTintList
		view.secondaryProgressTintList = foregroundTintList
		view.progressBackgroundTintList = ColorStateList.valueOf(
				updateStyleData.baseForegroundColor.withAlpha(StyleUpdater.DISABLED_ALPHA)
		)
	}

	private fun updateStyle(
			view: CircularProgressIndicator,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		view.setIndicatorColor(updateStyleData.baseForegroundColor)
		view.trackColor = updateStyleData.baseForegroundColor.withAlpha(StyleUpdater.DISABLED_ALPHA)
	}
	//endregion


	//region RecyclerView
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
	//endregion

	fun updateStyle(
			view: View,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		when (view) {
			is StyleableView -> view.onStyleChanged(StyleManager.styleData)
			is FluidSlider -> updateStyle(view, updateStyleData, backgroundColor)
			is CircularProgressIndicator -> updateStyle(view, updateStyleData, backgroundColor)
			is ProgressBar -> updateStyle(view, updateStyleData, backgroundColor)
			is ImageView -> imageViewStyleUpdater.updateStyle(
					view,
					updateStyleData,
					backgroundColor
			)
			is TextView -> textViewStyleUpdater.updateStyle(view, updateStyleData, backgroundColor)
		}
	}
}
