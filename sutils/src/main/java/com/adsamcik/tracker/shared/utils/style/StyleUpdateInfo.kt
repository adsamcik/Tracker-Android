package com.adsamcik.tracker.shared.utils.style

import com.adsamcik.tracker.shared.utils.style.update.abstraction.StyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.RequiredColors

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
