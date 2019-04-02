package com.adsamcik.signalcollector.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager


/**
 * Object that simplifies access to some preferences
 * It contains many preferences as constant values so they don't have to be stored in SharedPreferences which creates unnecessary lookup
 */
object Preferences {
	private var sharedPreferences: SharedPreferences? = null

	/**
	 * Get shared preferences
	 * This function should never crash. Initializes preferences if needed.
	 *
	 * @param c Non-null context
	 * @return Shared preferences
	 */
	fun getPref(c: Context): SharedPreferences {
		if (sharedPreferences == null)
			sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c.applicationContext)
		return sharedPreferences!!
	}
}