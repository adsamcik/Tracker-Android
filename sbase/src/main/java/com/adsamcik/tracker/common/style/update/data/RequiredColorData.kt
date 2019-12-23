package com.adsamcik.tracker.common.style.update.data

import androidx.annotation.StringRes

data class RequiredColors(val list: List<RequiredColorData>)

data class RequiredColorData(
		val defaultColor: Int,
		@StringRes
		val nameRes: Int
)
