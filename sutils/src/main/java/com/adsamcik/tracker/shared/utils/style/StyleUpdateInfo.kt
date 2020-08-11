package com.adsamcik.tracker.shared.utils.style

import com.adsamcik.tracker.shared.utils.style.update.abstraction.StyleUpdate
import com.adsamcik.tracker.shared.utils.style.update.data.DefaultColors

data class StyleUpdateInfo(
		val id: String,
		val nameRes: Int,
		val defaultColors: DefaultColors
) {
	internal constructor(update: StyleUpdate) : this(
			update.id,
			update.nameRes,
			update.defaultColors
	)
}
