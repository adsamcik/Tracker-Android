package com.adsamcik.tracker.shared.utils.style.update.data

import androidx.annotation.StringRes

data class RequiredColors(val list: List<RequiredColorData>)

data class RequiredColorData(
		val defaultColor: Int,
		@StringRes
		val nameRes: Int
)
