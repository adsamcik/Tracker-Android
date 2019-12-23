package com.adsamcik.tracker.shared.utils.style

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.View
import com.adsamcik.tracker.shared.utils.style.utility.ColorFunctions

internal class SystemStyleUpdater {
	private fun updateUiVisibility(view: View, luminance: Int) {
		assert(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
		@SuppressLint("InlinedApi")
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
				SystemBarStyle.Translucent, SystemBarStyle.Default -> Unit
			}
		}
	}
}
