package com.adsamcik.tracker.shared.base.extension

import androidx.core.graphics.ColorUtils

/**
 * Sets alpha component of a color
 */
fun Int.withAlpha(alpha: Int): Int = ColorUtils.setAlphaComponent(this, alpha)
