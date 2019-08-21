package com.adsamcik.tracker.common.style.marker

import com.adsamcik.tracker.common.style.StyleData

interface StyleableView {
	fun onStyleChanged(styleData: StyleData)
}
