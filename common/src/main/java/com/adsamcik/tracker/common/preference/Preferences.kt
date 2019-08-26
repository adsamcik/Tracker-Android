package com.adsamcik.tracker.common.preference

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import com.adsamcik.tracker.common.R
import com.adsamcik.tracker.common.misc.LengthSystem
import com.adsamcik.tracker.common.misc.SpeedFormat
import com.adsamcik.tracker.common.preference.observer.PreferenceObserver


/**
 * Object that simplifies access to some preferences
 * It contains many preferences as constant values so
 * they don't have to be stored in SharedPreferences which creates unnecessary lookup
 */
@Suppress("Unused", "TooManyFunctions")
open class Preferences {
	protected val resources: Resources
	protected val sharedPreferences: SharedPreferences

	constructor(context: Context) {
		resources = context.resources
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	}

	constructor(preferences: Preferences) {
		resources = preferences.resources
		sharedPreferences = preferences.sharedPreferences
	}

	fun getStringRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): String {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes)
		return getString(key, default)
	}

	fun getStringRes(@StringRes keyRes: Int): String? {
		val key = getKey(keyRes)
		return getString(key)
	}

	fun getString(key: String, default: String): String {
		return sharedPreferences.getString(key, default) ?: default
	}

	fun getString(key: String): String? {
		return sharedPreferences.getString(key, null)
	}

	fun getIntRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Int {
		val key = getKey(keyRes)
		val default = resources.getInteger(defaultRes)
		return getInt(key, default)
	}

	fun getIntResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Int {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toInt()
		return getInt(key, default)
	}

	fun getStringAsIntResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Int {
		return getStringRes(keyRes, defaultRes).toInt()
	}

	fun getStringAsInt(key: String, default: Int = 0): Int {
		return getString(key, default.toString()).toInt()
	}

	fun getInt(key: String, default: Int = 0): Int {
		return sharedPreferences.getInt(key, default)
	}

	fun getBooleanRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): Boolean {
		val key = getKey(keyRes)
		//This is fine, because getString is never null (@NonNull annotation)
		val default = resources.getString(defaultRes).toBoolean()
		return getBoolean(key, default)
	}

	fun getBoolean(key: String, default: Boolean = false): Boolean {
		return sharedPreferences.getBoolean(key, default)
	}

	fun getColorRes(@StringRes keyRes: Int, @ColorRes defaultRes: Int, theme: Resources.Theme? = null): Int {
		val key = getKey(keyRes)
		val color = ResourcesCompat.getColor(resources, defaultRes, theme)
		return getInt(key, color)
	}

	fun getLongRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Long {
		val key = getKey(keyRes)
		val default = resources.getInteger(defaultRes).toLong()
		return getLong(key, default)
	}

	fun getLongResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Long {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toLong()
		return getLong(key, default)
	}

	fun getLong(key: String, default: Long = 0L): Long {
		return sharedPreferences.getLong(key, default)
	}


	fun getFloatResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Float {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toFloat()
		return getFloat(key, default)
	}

	fun getFloatRes(@StringRes keyRes: Int, @DimenRes defaultRes: Int): Float {
		val key = getKey(keyRes)
		val default = ResourcesCompat.getFloat(resources, defaultRes)
		return getFloat(key, default)
	}

	fun getFloat(key: String, default: Float = Float.NaN): Float {
		return sharedPreferences.getFloat(key, default)
	}

	fun getDouble(key: String, default: Double = Double.NaN): Double {
		return Double.fromBits(getLong(key, default.toRawBits()))
	}


	open fun edit(func: MutablePreferences.() -> Unit) {
		MutablePreferences(this).edit(func)
	}

	protected fun getKey(@StringRes keyRes: Int): String {
		return resources.getString(keyRes)
	}


	companion object {
		private var preferences: MutablePreferences? = null

		/**
		 * Get shared preferences
		 * This function should never crash. Initializes preferences if needed.
		 *
		 * @param context Non-null context
		 * @return Shared preferences
		 */
		@Synchronized
		fun getPref(context: Context): Preferences {
			return getMutablePref(context)
		}

		private fun getMutablePref(context: Context): MutablePreferences {
			return preferences
					?: MutablePreferences(context).also {
						preferences = it
						PreferenceObserver.initialize(it.sharedPreferences)
					}
		}

		fun getLengthSystem(context: Context): LengthSystem {
			val preference = getPref(context).getStringRes(
					R.string.settings_length_system_key,
					R.string.settings_length_system_default
			)
			return LengthSystem.valueOf(preference)
		}

		fun getSpeedFormat(context: Context): SpeedFormat {
			val preference = getPref(context).getStringRes(
					R.string.settings_speed_format_key,
					R.string.settings_speed_format_default
			)
			return SpeedFormat.valueOf(preference)
		}
	}
}

