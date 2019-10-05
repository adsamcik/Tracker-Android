package com.adsamcik.tracker.common.assist

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Surface
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.extension.windowManager
import com.adsamcik.tracker.common.keyboard.NavBarPosition
import com.adsamcik.tracker.common.misc.Int2

object DisplayAssist {
	/**
	 * Returns orientation of the device as one of the following constants
	 * [Surface.ROTATION_0], [Surface.ROTATION_90], [Surface.ROTATION_180], [Surface.ROTATION_270].
	 *
	 * @return One of the following [Surface.ROTATION_0], [Surface.ROTATION_90],
	 * [Surface.ROTATION_180], [Surface.ROTATION_270]
	 */
	fun orientation(context: Context): Int {
		return context.windowManager.defaultDisplay.rotation
	}

	/**
	 * Calculates current navbar size and it's current position.
	 * Size is stored inside Point class.
	 *
	 * @param context Context
	 * @return (Position, Size)
	 */
	fun getNavigationBarSize(context: Context): Pair<NavBarPosition, Int2> {
		val display = context.windowManager.defaultDisplay

		val appUsableSize = Point()
		val realScreenSize = Point()

		display.getRealSize(realScreenSize)
		display.getSize(appUsableSize)
		val rotation = display.rotation

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
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> 24.dp
			else -> 25.dp
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
