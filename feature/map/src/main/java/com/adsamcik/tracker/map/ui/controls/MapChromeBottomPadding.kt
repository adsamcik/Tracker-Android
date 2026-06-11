package com.adsamcik.tracker.map.ui.controls

internal fun resolveMapChromeBottomPaddingPx(
	bottomInsetPx: Int,
	imeBottomPx: Int,
	gapPx: Int,
): Int = (imeBottomPx - bottomInsetPx).coerceAtLeast(0) + gapPx
