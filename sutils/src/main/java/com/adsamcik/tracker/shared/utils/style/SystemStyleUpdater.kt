package com.adsamcik.tracker.shared.utils.style

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.utils.style.utility.ColorConstants.QUARTER_COMPONENT
import com.adsamcik.tracker.shared.utils.style.utility.ColorConstants.TRANSPARENT
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions

internal class SystemStyleUpdater {
	private fun updateUiVisibility(view: View, luminance: Int) {
		when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> updateUiVisibilityR(view, luminance)
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> updateUiVisibilityMQ(view, luminance)
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

	@SuppressLint("InlinedApi")
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
				updateUiVisibility(styleView.view, perceivedLuminance)
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

	internal fun updateNavigationBar(styleView: SystemBarStyleView, styleData: StyleData) {
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
		val color = getSystemBarColor(styleView, styleData, perceivedLuminance)
		styleView.view.post {
			if (color != null) {
				styleView.window.navigationBarColor = color
			}
			updateUiVisibility(styleView.view, perceivedLuminance)
		}
	}

	internal fun updateStatusBar(styleView: SystemBarStyleView, styleData: StyleData) {
		val perceivedLuminance = styleData.perceivedLuminanceFor(styleView)
		val color = getSystemBarColor(styleView, styleData, perceivedLuminance)
		styleView.view.post {
			if (color != null) {
				styleView.window.statusBarColor = color
			}
			updateUiVisibility(styleView.view, perceivedLuminance)
		}
	}

	fun updateSystemBarStyle(
			notificationStyleView: SystemBarStyleView?,
			navigationStyleView: SystemBarStyleView?
	) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			updateFlagsR(notificationStyleView, navigationStyleView)
		} else {
			updateFlags(notificationStyleView, navigationStyleView)
		}
	}

	@Suppress("ComplexMethod", "ComplexCondition")
	@RequiresApi(Build.VERSION_CODES.R)
	private fun updateFlagsR(
			notificationStyleView: SystemBarStyleView?,
			navigationStyleView: SystemBarStyleView?
	) {
		require(notificationStyleView != null || navigationStyleView != null)
		val navigationStyle = navigationStyleView?.style ?: SystemBarStyle.Translucent
		val notificationStyle = notificationStyleView?.style ?: SystemBarStyle.Translucent
		var addFlags = 0
		var clearFlags = 0

		when (navigationStyle) {
			SystemBarStyle.Transparent, SystemBarStyle.LayerColor -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
			}
			else -> Unit
		}

		when (notificationStyle) {
			SystemBarStyle.Transparent, SystemBarStyle.LayerColor -> {
				addFlags = addFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
			}
			else -> Unit
		}

		if (notificationStyle.isBackgroundHandledBySystem && navigationStyle.isBackgroundHandledBySystem) {
			clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
		}

		if ((notificationStyle == SystemBarStyle.Transparent && navigationStyle != SystemBarStyle.Translucent) ||
				(navigationStyle == SystemBarStyle.Transparent && notificationStyle != SystemBarStyle.Translucent)) {
			addFlags = addFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		} else {
			clearFlags = clearFlags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
		}

		val window = notificationStyleView?.window ?: requireNotNull(navigationStyleView?.window)

		window.addFlags(addFlags)
		window.clearFlags(clearFlags)
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

		window.addFlags(addFlags)
		window.clearFlags(clearFlags)
	}
}
