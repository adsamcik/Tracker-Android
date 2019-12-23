package com.adsamcik.tracker.shared.utils.style.marker

import com.adsamcik.tracker.shared.utils.style.StyleData

interface StyleableView {
	fun onStyleChanged(styleData: StyleData)
}
