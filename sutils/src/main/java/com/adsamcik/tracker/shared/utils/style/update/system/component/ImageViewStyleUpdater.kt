package com.adsamcik.tracker.shared.utils.style.update.system.component

import android.content.res.ColorStateList
import android.os.Build
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatImageButton
import com.adsamcik.tracker.shared.base.extension.toTintList
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.shared.utils.style.update.system.StyleUpdater
import com.google.android.material.floatingactionbutton.FloatingActionButton

@Suppress("unused_parameter")
@MainThread
internal class ImageViewStyleUpdater {
	fun updateStyle(
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
			val colorStateList = ColorStateList.valueOf(updateStyleData.baseForegroundColor)
			view.imageTintList = colorStateList

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				view.foregroundTintList = colorStateList
			}
		}

		when (view) {
			is FloatingActionButton -> updateStyle(view, updateStyleData, backgroundColor)
			is AppCompatImageButton -> updateStyle(view, updateStyleData, backgroundColor)
		}
	}

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

	private fun updateStyle(
			view: AppCompatImageButton,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		view.backgroundTintList = backgroundColor.toTintList()
	}
}
