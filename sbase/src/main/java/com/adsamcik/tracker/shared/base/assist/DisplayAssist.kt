package com.adsamcik.tracker.shared.base.assist

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnPreDraw
import com.adsamcik.tracker.shared.base.BuildConfig
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.windowManager
import com.adsamcik.tracker.shared.base.misc.Int2
import com.adsamcik.tracker.shared.base.misc.NavBarPosition

/**
 * Utility object providing display methods.
 */
@Suppress("unused")
object DisplayAssist {
	private fun getDisplay(context: Context): Display = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireNotNull(context.display)
		else -> {
			val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
			requireNotNull(dm.getDisplay(Display.DEFAULT_DISPLAY))
		}
	}

	/**
	 * Returns orientation of the device as one of the following constants
	 * [Surface.ROTATION_0], [Surface.ROTATION_90], [Surface.ROTATION_180], [Surface.ROTATION_270].
	 *
	 * @return One of the following [Surface.ROTATION_0], [Surface.ROTATION_90],
	 * [Surface.ROTATION_180], [Surface.ROTATION_270]
	 */
	fun getOrientation(context: Context): Int {
		return getDisplay(context).rotation
	}

	fun getRealArea(context: Context): Int2 {
		return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			val windowMetrics = context.windowManager.currentWindowMetrics
			val bounds = windowMetrics.bounds
			Int2(bounds.width(), bounds.height())
		} else {
			val display = getDisplay(context)
			val realScreenSize = Point()
			@Suppress("DEPRECATION")
			display.getRealSize(realScreenSize)
			Int2(realScreenSize.x, realScreenSize.y)
		}
	}

	fun getUsableArea(context: Context): Int2 {
		return when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
				val windowMetrics = context.windowManager.currentWindowMetrics
				val bounds = windowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
				val realSize = getRealArea(context)

				Int2(
						realSize.x - bounds.left - bounds.right,
						realSize.y - bounds.top - bounds.bottom
				)
			}
			else -> {
				val display = getDisplay(context)
				val appUsableSize = Point()
				@Suppress("DEPRECATION")
				display.getSize(appUsableSize)

				Int2(appUsableSize.x, appUsableSize.y)
			}
		}
	}

	fun getDisplayOffsets(context: Context): Int2 {
		return when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
				val windowMetrics = context.windowManager.currentWindowMetrics
				val bounds = windowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())

				Int2(
						bounds.top + bounds.bottom,
						bounds.right + bounds.left
				)
			}
			else -> {
				val appUsableSize = getUsableArea(context)
				val realScreenSize = getRealArea(context)

				Int2(realScreenSize.x - appUsableSize.x, realScreenSize.y - appUsableSize.y)
			}
		}

	}

	/**
	 * Calculates current navbar size and it's current position.
	 * Size is stored inside Point class.
	 *
	 * @param context Context
	 * @return (Position, Size)
	 */
	fun getNavigationBarSize(context: Context): Pair<NavBarPosition, Int2> {
		val appUsableSize = getUsableArea(context)
		val realScreenSize = getRealArea(context)

		val rotation = getOrientation(context)

		// navigation bar on the right
		if (appUsableSize.x < realScreenSize.x) {
			//App supports only phones so there should be no scenario where orientation is 0 or 180
			val position = if (rotation == Surface.ROTATION_90 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				NavBarPosition.RIGHT
			} else {
				NavBarPosition.LEFT
			}

			val dimension = Int2(realScreenSize.x - appUsableSize.x, appUsableSize.y)

			return Pair(position, dimension)
		}

		// navigation bar at the bottom
		return if (appUsableSize.y < realScreenSize.y) {
			Pair(NavBarPosition.BOTTOM, Int2(appUsableSize.x, realScreenSize.y - appUsableSize.y))
		} else {
			Pair(NavBarPosition.UNKNOWN, Int2())
		}
	}

	fun Window.getStatusBarHeightDeferred(callback: (Int) -> Unit) {
		decorView.doOnPreDraw {
			val insets = ViewCompat.getRootWindowInsets(it)
			val height = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
			callback(height)
		}
	}

	fun calculateNoOfColumns(context: Context, columnWidthDp: Float): Int {
		val displayMetrics = context.resources.displayMetrics
		val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
		return (screenWidthDp / columnWidthDp + 0.5).toInt()
	}

	fun getDisplayDensity(context: Context): Float {
		return context.resources.displayMetrics.density
	}
}
