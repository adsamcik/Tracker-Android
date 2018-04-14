package com.adsamcik.signalcollector.uitools

import android.view.View

/**
 * Class that defines how should given view be colored
 *
 * @param view Root view (can, but does not have to be ViewGroup)
 * @param layer Layer of the [view], recursive layers are calculated appropriately
 * @param recursive Whether children of this view should be updated too
 * @param rootIsBackground True if root should have base color background
 * @param backgroundIsForeground True if view should have inverted background and foreground colors
 */
data class ColorView(val view: View,
                val layer: Int,
                val recursive: Boolean = true,
                val rootIsBackground: Boolean = true,
                val ignoreRoot: Boolean = false,
                val backgroundIsForeground: Boolean = false)