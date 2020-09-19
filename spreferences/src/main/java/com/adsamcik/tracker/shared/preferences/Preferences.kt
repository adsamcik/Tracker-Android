package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat

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

	/**
	 * Retrieve a string value from the preferences with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value
	 *
	 * @return String value or default if not present.
	 */
	fun getStringRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): String {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes)
		return getString(key, default)
	}

	/**
	 * Retrieve a string value from the preferences with resource key.
	 *
	 * @param keyRes String resource key for value
	 *
	 * @return Preference string value or null if not present.
	 */
	fun getStringRes(@StringRes keyRes: Int): String? {
		val key = getKey(keyRes)
		return getString(key)
	}

	/**
	 * Retrieve a string value from the preferences.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return String value or [default] if value is null or missing.
	 */
	fun getString(key: String, default: String): String {
		return getString(key) ?: default
	}

	/**
	 * Retrieve a string value from the preferences.
	 *
	 * @param key Preference key
	 *
	 * @return String value or null if value is null or missing.
	 */
	fun getString(key: String): String? {
		return sharedPreferences.getString(key, null)
	}

	/**
	 * Retrieve an integer value from the preferences with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes Integer resource key of default value
	 *
	 * @return Integer value or default if not present.
	 */
	fun getIntRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Int {
		val key = getKey(keyRes)
		val default = resources.getInteger(defaultRes)
		return getInt(key, default)
	}

	/**
	 * Retrieve an integer value from the preferences with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be an integer string resource)
	 *
	 * @return Integer value or default if not present.
	 */
	fun getIntResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Int {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toInt()
		return getInt(key, default)
	}

	/**
	 * Retrieve an integer value from a string preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be an integer string resource)
	 *
	 * @return Integer value or default if not present.
	 */
	fun getStringAsIntResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Int {
		return getStringRes(keyRes, defaultRes).toInt()
	}

	/**
	 * Retrieve an integer value from a string preference with key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Integer value or default if not present.
	 */
	fun getStringAsInt(key: String, default: Int = 0): Int {
		return getString(key, default.toString()).toInt()
	}

	/**
	 * Retrieve an integer value from an integer preference with key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Integer value or default if not present.
	 */
	fun getInt(key: String, default: Int = 0): Int {
		return sharedPreferences.getInt(key, default)
	}

	/**
	 * Retrieve a boolean value from a string preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be a boolean string resource)
	 *
	 * @return Boolean value or default if not present.
	 */
	fun getBooleanRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): Boolean {
		//This is fine, because getString is never null (@NonNull annotation)
		val default = resources.getString(defaultRes).toBoolean()
		return getBooleanRes(keyRes, default)
	}

	/**
	 * Retrieve a boolean value from a string preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param default Default value
	 *
	 * @return Boolean value or default if not present.
	 */
	fun getBooleanRes(@StringRes keyRes: Int, default: Boolean): Boolean {
		val key = getKey(keyRes)
		return getBoolean(key, default)
	}

	/**
	 * Retrieve a boolean value from a string preference with a key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Boolean value or default if not present.
	 */
	fun getBoolean(key: String, default: Boolean = false): Boolean {
		return sharedPreferences.getBoolean(key, default)
	}

	/**
	 * Retrieve a color value from an integer preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be a boolean string resource)
	 *
	 * @return Boolean value or default if not present.
	 */
	fun getColorRes(
			@StringRes keyRes: Int,
			@ColorRes defaultRes: Int,
			theme: Resources.Theme? = null
	): Int {
		val key = getKey(keyRes)
		val color = ResourcesCompat.getColor(resources, defaultRes, theme)
		return getInt(key, color)
	}

	/**
	 * Retrieve a long value from a long preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be a long string resource)
	 *
	 * @return Long value (default if not present).
	 */
	fun getLongRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Long {
		val key = getKey(keyRes)
		val default = resources.getInteger(defaultRes).toLong()
		return getLong(key, default)
	}

	/**
	 * Retrieve a long value from a long preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be a long string resource)
	 *
	 * @return Long value (default if not present).
	 */
	fun getLongResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Long {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toLong()
		return getLong(key, default)
	}

	/**
	 * Retrieve a long value from a long preference with key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Long value (default if not present).
	 */
	fun getLong(key: String, default: Long = 0L): Long {
		return sharedPreferences.getLong(key, default)
	}

	/**
	 * Retrieve a float value from a float preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes String resource key of default value (must be a float string resource)
	 *
	 * @throws NumberFormatException If default value cannot be converted to float.
	 *
	 * @return Float value (default if not present).
	 */
	fun getFloatResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Float {
		val key = getKey(keyRes)
		val default = resources.getString(defaultRes).toFloat()
		return getFloat(key, default)
	}

	/**
	 * Retrieve a float value from a float preference with resource key.
	 *
	 * @param keyRes String resource key for key value
	 * @param defaultRes Dimen (float) resource key of default value
	 *
	 * @return Float value (default if not present).
	 */
	fun getFloatRes(@StringRes keyRes: Int, @DimenRes defaultRes: Int): Float {
		val key = getKey(keyRes)
		val default = ResourcesCompat.getFloat(resources, defaultRes)
		return getFloat(key, default)
	}

	/**
	 * Retrieve a float value from a float preference with key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Float value (default if not present).
	 */
	fun getFloat(key: String, default: Float = Float.NaN): Float {
		return sharedPreferences.getFloat(key, default)
	}

	/**
	 * Retrieve a double value from a long preference with key.
	 *
	 * @param key Preference key
	 * @param default Default value
	 *
	 * @return Float value (default if not present).
	 */
	fun getDouble(key: String, default: Double = Double.NaN): Double {
		return Double.fromBits(getLong(key, default.toRawBits()))
	}

	/**
	 * Executes transaction for modifying preferences.
	 *
	 * @param func Function scope in which edits are executed. Executed with [MutablePreferences] scope.
	 */
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
			return getMutablePref(
					context
			)
		}

		private fun getMutablePref(context: Context): MutablePreferences {
			return preferences
					?: MutablePreferences(context).also {
						preferences = it
						PreferenceObserver.initialize(it.sharedPreferences)
					}
		}

		/**
		 * Utility method to get current [LengthSystem] preference.
		 *
		 * @param context Context
		 *
		 * @return Current [LengthSystem] preference
		 */
		fun getLengthSystem(context: Context): LengthSystem {
			val preference = getPref(
					context
			).getStringRes(
					R.string.settings_length_system_key,
					R.string.settings_length_system_default
			)
			return LengthSystem.valueOf(preference)
		}

		/**
		 * Utility method to get current [SpeedFormat] preference.
		 *
		 * @param context Context
		 *
		 * @return Current [SpeedFormat] preference
		 */
		fun getSpeedFormat(context: Context): SpeedFormat {
			val preference = getPref(
					context
			).getStringRes(
					R.string.settings_speed_format_key,
					R.string.settings_speed_format_default
			)
			return SpeedFormat.valueOf(preference)
		}
	}
}

