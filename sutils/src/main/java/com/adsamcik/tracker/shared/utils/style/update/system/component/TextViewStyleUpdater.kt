package com.adsamcik.tracker.shared.utils.style.update.system.component

import android.content.res.ColorStateList
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.alpha
import androidx.core.widget.TextViewCompat
import com.adsamcik.tracker.shared.base.extension.firstParent
import com.adsamcik.tracker.shared.utils.style.marker.StyleableForegroundDrawable
import com.adsamcik.tracker.shared.utils.style.update.system.StyleUpdater
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

@Suppress("unused_parameter")
@MainThread
internal class TextViewStyleUpdater {
	private val buttonStyleUpdater = ButtonStyleUpdater()

	fun updateStyle(
			view: TextView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
		val alpha = view.textColors.defaultColor.alpha
		val colorStateList = updateStyleData.getBaseTextColorStateList(alpha)

		view.setTextColor(colorStateList)

		val hintColorState = colorStateList.withAlpha(alpha - StyleUpdater.HINT_TEXT_ALPHA_OFFSET)
		view.setHintTextColor(hintColorState)

		when (view) {
			is Button -> buttonStyleUpdater.updateStyle(view, updateStyleData, backgroundColor)
			is AppCompatTextView -> updateStyle(view, updateStyleData, backgroundColor)
			is TextInputEditText -> updateStyle(view, updateStyleData, hintColorState)
		}
	}

	private fun updateStyle(
			view: AppCompatTextView,
			updateStyleData: StyleUpdater.UpdateStyleData,
			@ColorInt backgroundColor: Int
	) {
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

	private fun updateStyle(
			view: TextInputEditText,
			updateStyleData: StyleUpdater.UpdateStyleData,
			hintColorState: ColorStateList
	) {
		val parent = view.firstParent<TextInputLayout>(1)
		require(parent is TextInputLayout) {
			"TextInputEditText ($view) should always have TextInputLayout as it's parent! Found $parent instead"
		}

		parent.defaultHintTextColor = hintColorState
	}
}
