package com.adsamcik.signalcollector.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.misc.LengthSystem


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
	 * @param context Non-null context
	 * @return Shared preferences
	 */
	fun getPref(context: Context): SharedPreferences {
		return this.sharedPreferences
				?: PreferenceManager.getDefaultSharedPreferences(context.applicationContext).apply { this@Preferences.sharedPreferences = this }
	}


	fun getLengthSystem(context: Context): LengthSystem {
		val resources = context.resources
		val preference = getPref(context).getString(resources.getString(R.string.settings_length_system_key), resources.getString(R.string.settings_length_system_default))
				?: throw NullPointerException("Length system string cannot be null!")
		return LengthSystem.valueOf(preference)
	}
}