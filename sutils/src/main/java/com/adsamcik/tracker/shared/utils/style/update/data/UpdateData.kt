package com.adsamcik.tracker.shared.utils.style.update.data

import androidx.annotation.ColorInt

data class UpdateData(
		@ColorInt
		val fromColor: Int,
		@ColorInt
		val toColor: Int,
		val duration: Long,
		var progress: Long
)
