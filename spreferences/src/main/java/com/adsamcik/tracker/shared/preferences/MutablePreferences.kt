package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * Allows for modification of preferences.
 */
@Suppress("UNUSED", "PRIVATE", "TooManyFunctions", "MemberVisibilityCanBePrivate")
class MutablePreferences : Preferences {
	private val editor: SharedPreferences.Editor = sharedPreferences.edit()

	constructor(context: Context) : super(context)
	constructor(preferences: Preferences) : super(preferences)

	/**
	 * Set string preference with resource key [keyRes] to [value].
	 *
	 * @param keyRes String resource key for key value
	 * @param value New value
	 */
	fun setString(@StringRes keyRes: Int, value: String) {
		val key = getKey(keyRes)
		setString(key, value)
	}

	/**
	 * Set string preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setString(key: String, value: String) {
		editor.putString(key, value)
	}

	/**
	 * Set integer preference with resource key [keyRes] to [value].
	 *
	 * @param keyRes String resource key for key value
	 * @param value New value
	 */
	fun setInt(@StringRes keyRes: Int, value: Int) {
		val key = getKey(keyRes)
		setInt(key, value)
	}

	/**
	 * Set integer preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setInt(key: String, value: Int) {
		editor.putInt(key, value)
	}

	/**
	 * Set boolean preference with resource key [keyRes] to [value].
	 *
	 * @param keyRes String resource key for key value
	 * @param value New value
	 */
	fun setBoolean(@StringRes keyRes: Int, value: Boolean) {
		val key = getKey(keyRes)
		setBoolean(key, value)
	}

	/**
	 * Set boolean preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setBoolean(key: String, value: Boolean) {
		editor.putBoolean(key, value)
	}

	/**
	 * Set long preference with resource key [keyRes] to [value].
	 *
	 * @param keyRes String resource key for key value
	 * @param value New value
	 */
	fun setLong(@StringRes keyRes: Int, value: Long) {
		val key = getKey(keyRes)
		setLong(key, value)
	}

	/**
	 * Set long preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setLong(key: String, value: Long) {
		editor.putLong(key, value)
	}

	/**
	 * Set float preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setFloat(key: String, value: Float) {
		editor.putFloat(key, value)
	}

	/**
	 * Set double preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setDouble(key: String, value: Double) {
		setLong(key, value.toRawBits())
	}

	/**
	 * Remove preference with resource key [keyRes].
	 */
	fun remove(@StringRes keyRes: Int) {
		val key = getKey(keyRes)
		remove(key)
	}

	/**
	 * Remove preference with key [key].
	 */
	fun remove(key: String) {
		editor.remove(key)
	}

	/**
	 * Remove all preference with given key prefix.
	 * Useful for clearing groups with common prefix.
	 * Needs to be used with caution as it may remove unwanted preferences.
	 */
	fun removeKeyByPrefix(prefix: String) {
		sharedPreferences
				.all
				.filter { it.key.startsWith(prefix) }
				.forEach { remove(it.key) }
	}

	/**
	 * Remove all preferences
	 */
	fun clear() {
		editor.clear()
	}

	/**
	 * Apply all changes to preferences
	 */
	fun apply() {
		editor.apply()
	}

	override fun edit(func: MutablePreferences.() -> Unit) {
		func(this)
		apply()
	}
}
