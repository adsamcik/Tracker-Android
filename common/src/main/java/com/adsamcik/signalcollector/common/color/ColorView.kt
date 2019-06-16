package com.adsamcik.signalcollector.common.color

import android.view.View
import androidx.annotation.IntRange

/**
 * Class that defines how should given view be colored
 *
 * @param view Root view (can, but does not have to be ViewGroup)
 * @param layer Layer of the [view], recursive layers are calculated appropriately
 * @param maxDepth Max depth to which view is updated
 * @param isBackgroundEnabled True if background should be placed
 * @param isInverted True if view should have inverted background and foreground colors
 */
data class ColorView(val view: View,
                     val layer: Int,
                     @IntRange(from = 0, to = Int.MAX_VALUE.toLong())
                     val maxDepth: Int = Int.MAX_VALUE,
                     val isBackgroundEnabled: Boolean = true,
                     val isInverted: Boolean = false)