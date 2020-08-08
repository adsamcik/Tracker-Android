package com.adsamcik.tracker.shared.utils.style

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions

internal class SystemStyleUpdater {
	private fun updateUiVisibility(view: View, luminance: Int) {
		when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> updateUiVisibilityR(view, luminance)
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> updateUiVisibilityMQ(view, luminance)
			else -> throw IllegalStateException()
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun updateUiVisibilityR(view: View, luminance: Int) {
		val insetsController = requireNotNull(view.windowInsetsController);

		val statusBarAppearance: Int
		val navBarAppearance: Int

		if (luminance > 0) {
			statusBarAppearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
			navBarAppearance = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
		} else {
			statusBarAppearance = 0;
			navBarAppearance = 0;
		}

		insetsController.setSystemBarsAppearance(
				statusBarAppearance,
				WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
		);

		insetsController.setSystemBarsAppearance(
				navBarAppearance,
				WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
		);
	}

	@RequiresApi(Build.VERSION_CODES.M)
	private fun updateUiVisibilityMQ(view: View, luminance: Int) {
		require(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
		@Suppress("DEPRECATION")
		view.systemUiVisibility = if (luminance > 0) {
			view.systemUiVisibility or
					View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
					View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
		} else {
			view.systemUiVisibility and
					(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
							View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
		}
	}

	internal fun updateNavigationBar(styleView: SystemBarStyleView, styleData: StyleData) {
		styleView.view.post {
			when (styleView.style) {
				SystemBarStyle.LayerColor -> {
					val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
					val backgroundColor = styleData.backgroundColorFor(styleView)
					updateUiVisibility(styleView.view, perceivedLuminance)

					styleView.window.navigationBarColor = ColorFunctions.getBackgroundLayerColor(
							backgroundColor,
							perceivedLuminance,
							styleView.layer
					)
				}
				SystemBarStyle.Transparent -> {
					val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
					updateUiVisibility(styleView.view, perceivedLuminance)
					styleView.window.navigationBarColor = Color.TRANSPARENT
				}
				SystemBarStyle.Translucent, SystemBarStyle.Default -> Unit
			}
		}
	}

	internal fun updateNotificationBar(styleView: SystemBarStyleView, styleData: StyleData) {
		styleView.view.post {
			when (styleView.style) {
				SystemBarStyle.LayerColor -> {
					val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
					val backgroundColor = styleData.backgroundColorFor(styleView)
					updateUiVisibility(styleView.view, perceivedLuminance)

					styleView.window.statusBarColor = ColorFunctions.getBackgroundLayerColor(
							backgroundColor,
							perceivedLuminance,
							styleView.layer
					)
				}
				SystemBarStyle.Transparent -> {
					val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
					updateUiVisibility(styleView.view, perceivedLuminance)
					styleView.window.statusBarColor = Color.TRANSPARENT
				}
				SystemBarStyle.Translucent, SystemBarStyle.Default -> {
					styleView.window.statusBarColor = Color.argb(128, 255, 255, 255)
				}
			}
		}
	}

	fun updateSystemBarStyle(
			notificationStyleView: SystemBarStyleView?,
			navigationStyleView: SystemBarStyleView?
	) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			updateSystemBarStyleR(notificationStyleView, navigationStyleView)
		} else {
			updateFlags(notificationStyleView, navigationStyleView)
		}
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private fun updateSystemBarStyleR(
			notificationStyleView: SystemBarStyleView?,
			navigationStyleView: SystemBarStyleView?
	) {
		require(notificationStyleView != null || navigationStyleView != null)
		val view = navigationStyleView?.view ?: requireNotNull(notificationStyleView).view
		val navigationStyle = navigationStyleView?.style ?: SystemBarStyle.Translucent
		val notificationStyle = notificationStyleView?.style ?: SystemBarStyle.Translucent

		val insetController = requireNotNull(view.windowInsetsController)


		/*when (navigationStyle) {
			SystemBarStyle.Translucent -> {
				insetController.setSystemBarsAppearance(
						android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS,
						WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS
				)
				addFlags = addFlags or
						WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
			}
			SystemBarStyle.Transparent, SystemBarStyle.LayerColor -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
				clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
			}
			SystemBarStyle.Default -> Unit
		}*/
	}


	@Suppress("ComplexMethod", "ComplexCondition")
	private fun updateFlags(
			notificationStyleView: SystemBarStyleView?,
			navigationStyleView: SystemBarStyleView?
	) {
		require(notificationStyleView != null || navigationStyleView != null)
		val navigationStyle = navigationStyleView?.style ?: SystemBarStyle.Translucent
		val notificationStyle = notificationStyleView?.style ?: SystemBarStyle.Translucent
		var addFlags = 0
		var clearFlags = 0

		when (navigationStyle) {
			SystemBarStyle.Translucent -> {
				addFlags = addFlags or
						WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
			}
			SystemBarStyle.Transparent, SystemBarStyle.LayerColor -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
				clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
			}
			SystemBarStyle.Default -> Unit
		}

		when (notificationStyle) {
			SystemBarStyle.Translucent -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
			}
			SystemBarStyle.Transparent, SystemBarStyle.LayerColor -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
				clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
			}
			SystemBarStyle.Default -> {
				clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
			}
		}

		if (notificationStyle.isBackgroundHandledBySystem && navigationStyle.isBackgroundHandledBySystem) {
			clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
			//clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		}

		if ((notificationStyle == SystemBarStyle.Transparent && navigationStyle != SystemBarStyle.Translucent) ||
				(navigationStyle == SystemBarStyle.Transparent && notificationStyle != SystemBarStyle.Translucent)) {
			addFlags = addFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		} else {
			clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		}

		addFlags = addFlags or WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
		clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_FULLSCREEN

		val window = notificationStyleView?.window ?: requireNotNull(navigationStyleView?.window)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			// Crashes, seems to be android R bug
			window.insetsController?.hide(WindowInsets.Type.systemBars())
		}

		window.addFlags(addFlags)
		window.clearFlags(clearFlags)
	}
}
