package com.adsamcik.signalcollector.uitools

import android.view.View

class ColorView(val view: View,
                val layer: Int,
                val recursive: Boolean = true,
                val rootIsBackground: Boolean = true,
                val ignoreRoot: Boolean = false,
                val backgroundIsForeground: Boolean = false)