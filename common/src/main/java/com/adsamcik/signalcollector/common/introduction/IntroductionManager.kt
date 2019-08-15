package com.adsamcik.signalcollector.common.introduction

import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.introduction.IntroductionManager.showIntroduction
import com.adsamcik.signalcollector.common.preference.Preferences
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
		val preferences = Preferences.getPref(activity)
		try {
			return if (!anyShown &&
					preferences.getBooleanRes(R.string.show_tips_key, R.string.show_tips_default) &&
					!preferences.getBoolean(introduction.preference, false)) {

				val targets = introduction.getTargets(activity)

				if (targets.isEmpty()) return false

				Spotlight.with(activity)
						.setTargets(targets)
						.setOverlayColor(ColorUtils.setAlphaComponent(Color.BLACK, 230))
						.setAnimation(AccelerateDecelerateInterpolator())
						.setOnSpotlightEndedListener {
							Preferences.getPref(activity)
									.edit { setBoolean(introduction.preference, true) }
							introduction.onDone()
							onDone()
						}
						.start()

				anyShown = true
				true
			} else {
				false
			}
		} catch (e: Exception) {
			Reporter.report(e)
			return false
		}
	}

	private fun onDone() {
		anyShown = false
	}
}

