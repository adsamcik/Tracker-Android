package com.adsamcik.tracker.shared.utils.style

import android.view.View
import android.view.Window
import androidx.annotation.IntRange
import androidx.recyclerview.widget.RecyclerView

/**
 * Defines basic properties of any style view.
 */
interface BaseStyleView {
	/**
	 * View that is styled/
	 */
	val view: View

	/**
	 * Layer of the styled view/
	 */
	val layer: Int

	/**
	 * If true, background and foreground colors are inverted.
	 */
	val isInverted: Boolean

	/**
	 * Max depth that should be traversed to update views children.
	 */
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


/**
 * Defines style properties of Recycler View.
 *
 * @param view Recycler view
 * @param layer Base layer of the recycler view
 * @param childrenLayer Layer of recycler view children
 * @param onlyChildren If true, ignore recycler view and only update children style
 * @param isInverted True if background and foreground should be inverted
 */
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

/**
 * Description of system bar style.
 *
 * @param window Window
 * @param layer System bar layer
 * @param style System bar style
 */
data class SystemBarStyleView(
		val window: Window,
		override val layer: Int,
		val style: SystemBarStyle
) : BaseStyleView {
	override val maxDepth: Int = 0
	override val view: View get() = window.decorView
	override val isInverted: Boolean = false
}

/**
 * Enum of available system bar styles.
 */
enum class SystemBarStyle {
	Default {
		override val isBackgroundHandledBySystem: Boolean = true
		override val isSeeThrough: Boolean = false
	},
	LayerColor {
		override val isBackgroundHandledBySystem: Boolean = false
		override val isSeeThrough: Boolean = false
	},
	Transparent {
		override val isBackgroundHandledBySystem: Boolean = false
		override val isSeeThrough: Boolean = true
	},
	Translucent {
		override val isBackgroundHandledBySystem: Boolean = true
		override val isSeeThrough: Boolean = true
	};

	abstract val isBackgroundHandledBySystem: Boolean
	abstract val isSeeThrough: Boolean
}

