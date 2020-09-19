package com.adsamcik.tracker.shared.base.extension

import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils

/**
 * Sets alpha component of a color
 */
fun Int.withAlpha(alpha: Int): Int = ColorUtils.setAlphaComponent(this, alpha)

/**
 * Converts color to [ColorStateList]
 */
fun Int.toTintList(): ColorStateList = ColorStateList.valueOf(this)
