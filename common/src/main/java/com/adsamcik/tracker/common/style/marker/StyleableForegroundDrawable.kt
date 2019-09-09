package com.adsamcik.tracker.common.style.marker

import android.content.res.ColorStateList

interface StyleableForegroundDrawable {
	fun onForegroundStyleChanged(foregroundColor: ColorStateList)
}
