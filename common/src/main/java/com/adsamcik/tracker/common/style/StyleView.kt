package com.adsamcik.tracker.common.style

import android.view.View
import android.view.Window
import androidx.annotation.IntRange
import androidx.recyclerview.widget.RecyclerView

interface BaseStyleView {
	val view: View
	val layer: Int
	val isInverted: Boolean
	val maxDepth: Int
}

/**
 * Class that defines how should given view be colored
 *
 * @param view Root view (can, but does not have to be ViewGroup)
 * @param layer Layer of the [view], recursive layers are calculated appropriately
 * @param maxDepth Max depth to which view is updated
 * @param isBackgroundEnabled True if background should be placed
 * @param isInverted True if view should have inverted background and foreground colors
 */
data class StyleView(
		override val view: View,
		override val layer: Int,
		@IntRange(from = 0, to = Int.MAX_VALUE.toLong())
		override val maxDepth: Int = Int.MAX_VALUE,
		val isBackgroundEnabled: Boolean = true,
		override val isInverted: Boolean = false
) : BaseStyleView


data class RecyclerStyleView(
		override val view: RecyclerView,
		override val layer: Int = 0,
		val childrenLayer: Int = layer,
		val onlyChildren: Boolean = false,
		override val isInverted: Boolean = false
) : BaseStyleView {
	override val maxDepth: Int
		get() = Int.MAX_VALUE
}

data class NotificationStyleView(
		val window: Window,
		override val layer: Int,
		val style: NotificationStyle
) : BaseStyleView {
	override val maxDepth: Int = 0
	override val view: View get() = window.decorView
	override val isInverted: Boolean = false
}

enum class NotificationStyle {
	Default,
	LayerColor,
	Transparent,
	Translucent
}

