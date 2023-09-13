package com.adsamcik.tracker.shared.utils.introduction

import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.adsamcik.tracker.shared.base.extension.withAlpha
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.utils.extension.tryWithReport
import com.adsamcik.tracker.shared.utils.introduction.IntroductionManager.showIntroduction
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
		tryWithReport {
			if (!anyShown &&
					preferences.getBooleanRes(
							com.adsamcik.tracker.shared.preferences.R.string.show_tips_key,
							com.adsamcik.tracker.shared.preferences.R.string.show_tips_default
					) &&
					!preferences.getBoolean(introduction.preference, false)) {

				val targets = introduction.getTargets(activity)

				if (targets.isNotEmpty()) {
					Spotlight.with(activity)
							.setTargets(targets)
							.setOverlayColor(Color.BLACK.withAlpha(230))
							.setAnimation(AccelerateDecelerateInterpolator())
							.setOnSpotlightEndedListener {
								Preferences.getPref(activity)
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

