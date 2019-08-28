package com.adsamcik.tracker.common.style

import com.adsamcik.tracker.common.style.update.RequiredColors
import com.adsamcik.tracker.common.style.update.StyleUpdate

data class StyleUpdateInfo(val nameRes: Int, val requiredColors: RequiredColors) {
	constructor(update: StyleUpdate) : this(update.nameRes, update.requiredColorData)
}
