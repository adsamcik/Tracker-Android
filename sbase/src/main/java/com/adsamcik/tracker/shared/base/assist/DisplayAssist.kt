package com.adsamcik.tracker.shared.base.assist

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Surface
import android.view.WindowInsets
import com.adsamcik.tracker.shared.base.extension.dp
import com.adsamcik.tracker.shared.base.extension.windowManager
import com.adsamcik.tracker.shared.base.misc.Int2
import com.adsamcik.tracker.shared.base.misc.NavBarPosition

/**
 * Utility object providing display methods.
 */
@Suppress("unused")
object DisplayAssist {
	private fun getDisplay(context: Context) = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requireNotNull(context.display)
		else -> @Suppress("DEPRECATION") context.windowManager.defaultDisplay
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
			else -> @Suppress("DEPRECATION") {
				val display = context.windowManager.defaultDisplay
				val appUsableSize = Point()
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

	//todo consider using WindowInsets
	@Suppress("MagicNumber")
	fun getStatusBarHeight(context: Context): Int {
		val resources = context.resources
		val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
		return when {
			resourceId > 0 -> resources.getDimensionPixelSize(resourceId)
			else -> 24.dp
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
