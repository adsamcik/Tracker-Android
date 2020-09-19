package com.adsamcik.tracker.shared.utils.style.marker

import android.content.res.ColorStateList

interface StyleableForegroundDrawable {
	fun onForegroundStyleChanged(foregroundColor: ColorStateList)
}
