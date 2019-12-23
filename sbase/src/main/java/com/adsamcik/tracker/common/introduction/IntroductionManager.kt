package com.adsamcik.tracker.common.introduction

import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.extension.tryWithReport
import com.adsamcik.tracker.common.introduction.IntroductionManager.showIntroduction
import com.adsamcik.tracker.common.preferences.Preferences
import com.takusemba.spotlight.Spotlight

/**
 * Singleton class handling displaying of IntroductionManager based on [showIntroduction] calls
 */
object IntroductionManager {
	/**
	 * returns true if any tip is currently shown
	 */
	var anyShown: Boolean = false
		private set

	/**
	 * Shows tips for a given key
	 * Exception is thrown if key is not valid
	 *
	 * Function also performs check if tips can be shown and whether given tip was already shown
	 *
	 * @param activity Activity used to display tips
	 * @param introduction Introduction object containing proper introduction
	 */
	fun showIntroduction(activity: FragmentActivity, introduction: Introduction): Boolean {
		val preferences = com.adsamcik.tracker.common.preferences.Preferences.getPref(activity)
		tryWithReport {
			if (!anyShown &&
					preferences.getBooleanRes(R.string.show_tips_key, R.string.show_tips_default) &&
					!preferences.getBoolean(introduction.preference, false)) {

				val targets = introduction.getTargets(activity)

				if (targets.isNotEmpty()) {
					Spotlight.with(activity)
							.setTargets(targets)
							.setOverlayColor(ColorUtils.setAlphaComponent(Color.BLACK, 230))
							.setAnimation(AccelerateDecelerateInterpolator())
							.setOnSpotlightEndedListener {
								com.adsamcik.tracker.common.preferences.Preferences.getPref(activity)
										.edit { setBoolean(introduction.preference, true) }
								introduction.onDone()
								onDone()
							}
							.start()

					anyShown = true
					return true
				}
			}
		}
		return false
	}

	private fun onDone() {
		anyShown = false
	}
}

