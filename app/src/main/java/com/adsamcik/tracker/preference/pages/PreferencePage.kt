package com.adsamcik.tracker.preference.pages

import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat

/**
 * A preference interface describing base Page methods.
 */
interface PreferencePage {
	/**
	 * Called when page is opened.
	 */
	fun onEnter(caller: PreferenceFragmentCompat)

	/**
	 * Called when page is left.
	 */
	fun onExit(caller: PreferenceFragmentCompat)

	/**
	 * Called when permission request returns results.
	 */
	fun onRequestPermissionsResult(
			activity: FragmentActivity,
			code: Int,
			result: Collection<Pair<String, Int>>
	) {
	}

	/**
	 * Called to register all requests
	 */
	fun onRegisterForResult(activity: FragmentActivity) {}
}
