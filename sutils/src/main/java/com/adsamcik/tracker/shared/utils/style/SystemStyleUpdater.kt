package com.adsamcik.tracker.shared.utils.style

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.AnyThread
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.extension.runOnUiThread
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.QUARTER_COMPONENT
import com.adsamcik.tracker.shared.utils.style.color.ColorConstants.TRANSPARENT
import com.adsamcik.tracker.shared.utils.style.color.ColorFunctions

@AnyThread
internal class SystemStyleUpdater {
	private fun updateSystemBarAppearance(view: View, luminance: Int) {
		when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> updateSystemBarAppearanceR(
					view,
					luminance
			)
			else -> updateSystemBarAppearancePreR(
					view,
					luminance
			)
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun updateSystemBarAppearanceR(view: View, luminance: Int) {
		val windowInsetsController = view.windowInsetsController ?: return

		val isLight = luminance > 0

		val appearance = if (isLight) {
			WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
			WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
		} else {
			0
		}

		windowInsetsController.setSystemBarsAppearance(
			appearance,
			WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
					WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
		)
	}


	@SuppressLint("InlinedApi")
	private fun updateSystemBarAppearancePreR(view: View, luminance: Int) {
		val isLight = luminance > 0

		@Suppress("DEPRECATION")
		view.post {
			var flags = view.systemUiVisibility

			if (isLight) {
				flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
				}
			} else {
				flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
				}
			}

			view.systemUiVisibility = flags
		}
	}

	private fun getSystemBarColor(
			styleView: SystemBarStyleView,
			styleData: StyleData,
			perceivedLuminance: Int = styleData.perceivedLuminanceFor(styleView)
	): Int? {
		return when (styleView.style) {
			SystemBarStyle.LayerColor -> {
				val backgroundColor = styleData.backgroundColorFor(styleView)

				ColorFunctions.getBackgroundLayerColor(
						backgroundColor,
						perceivedLuminance,
						styleView.layer
				)
			}
			SystemBarStyle.Transparent -> {
				updateSystemBarAppearance(styleView.view, perceivedLuminance)
				TRANSPARENT
			}
			SystemBarStyle.Translucent -> {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					styleData
							.backgroundColor(isInverted = perceivedLuminance > 0)
							.withAlpha(QUARTER_COMPONENT)
				} else {
					null
				}
			}
			SystemBarStyle.Default -> null
		}
	}

	@AnyThread
	internal fun updateNavigationBar(styleView: SystemBarStyleView, styleData: StyleData) {
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
		val color = getSystemBarColor(styleView, styleData, perceivedLuminance)
		if (color != null) {
			styleView.view.runOnUiThread {
				styleView.window.navigationBarColor = color
			}
		}
		updateSystemBarAppearance(styleView.view, perceivedLuminance)
	}

	@AnyThread
	internal fun updateStatusBar(styleView: SystemBarStyleView, styleData: StyleData) {
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
		val color = getSystemBarColor(styleView, styleData, perceivedLuminance)
		if (color != null) {
			styleView.view.runOnUiThread {
				styleView.window.statusBarColor = color
			}
		}
		updateSystemBarAppearance(styleView.view, perceivedLuminance)
	}

	private fun updateSystemBarStyle(
		notificationStyleView: SystemBarStyleView?,
		navigationStyleView: SystemBarStyleView?
	) {
		val window = notificationStyleView?.window ?: navigationStyleView?.window ?: return

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			updateFlagsR(window, notificationStyleView?.style, navigationStyleView?.style)
		} else {
			updateFlagsPreR(window, notificationStyleView?.style, navigationStyleView?.style)
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun updateFlagsR(
		window: Window,
		notificationStyle: SystemBarStyle?,
		navigationStyle: SystemBarStyle?
	) {
		var flags = window.attributes.flags

		// Clear deprecated flags
		flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
		flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION.inv()

		// Add necessary flags
		flags = flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

		// Apply layout parameters
		window.attributes.flags = flags

		// Handle transparent status bar
		if (notificationStyle == SystemBarStyle.Transparent) {
			window.setDecorFitsSystemWindows(false)
			window.statusBarColor = Color.TRANSPARENT
		}

		// Handle transparent navigation bar
		if (navigationStyle == SystemBarStyle.Transparent) {
			window.setDecorFitsSystemWindows(false)
			window.navigationBarColor = Color.TRANSPARENT
		}
	}


	@Suppress("DEPRECATION")
	private fun updateFlagsPreR(
		window: Window,
		notificationStyle: SystemBarStyle?,
		navigationStyle: SystemBarStyle?
	) {
		var flags = window.attributes.flags

		// Handle translucent status bar
		if (notificationStyle == SystemBarStyle.Translucent) {
			flags = flags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
		} else {
			flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
		}

		// Handle translucent navigation bar
		if (navigationStyle == SystemBarStyle.Translucent) {
			flags = flags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
		} else {
			flags = flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION.inv()
		}

		// Add necessary flags
		flags = flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

		// Apply layout parameters
		window.attributes.flags = flags

		// Handle transparent status bar
		if (notificationStyle == SystemBarStyle.Transparent) {
			window.statusBarColor = Color.TRANSPARENT
		}

		// Handle transparent navigation bar
		if (navigationStyle == SystemBarStyle.Transparent) {
			window.navigationBarColor = Color.TRANSPARENT
		}
	}
}
