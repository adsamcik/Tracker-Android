package com.adsamcik.tracker.preference.pages

import android.content.Context
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
			context: Context,
			code: Int,
			result: Collection<Pair<String, Int>>
	) {
	}
}
