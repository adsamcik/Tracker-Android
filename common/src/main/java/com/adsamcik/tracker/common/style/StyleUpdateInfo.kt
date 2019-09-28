package com.adsamcik.tracker.common.style

import com.adsamcik.tracker.common.style.update.data.RequiredColors
import com.adsamcik.tracker.common.style.update.abstraction.StyleUpdate

data class StyleUpdateInfo(
		val id: String,
		val nameRes: Int,
		val requiredColors: RequiredColors
) {
	internal constructor(update: StyleUpdate) : this(
			update.id,
			update.nameRes,
			update.requiredColorData
	)
}
