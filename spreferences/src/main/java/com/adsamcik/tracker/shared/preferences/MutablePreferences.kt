package com.adsamcik.tracker.shared.preferences

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.MutablePreferences as DataMutablePreferences
import androidx.datastore.preferences.core.Preferences as DataPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore

/**
 * Allows for modification of preferences.
 */
@Suppress("UNUSED", "PRIVATE", "TooManyFunctions", "MemberVisibilityCanBePrivate")
class MutablePreferences : Preferences {
	private val operations = mutableListOf<(DataMutablePreferences) -> Unit>()

	constructor(context: Context) : super(context)
	constructor(preferences: Preferences) : super(preferences)

	private fun enqueue(operation: (DataMutablePreferences) -> Unit) {
		operations.add(operation)
	}

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
		enqueue { prefs -> prefs[stringPreferencesKey(key)] = value }
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
		enqueue { prefs -> prefs[intPreferencesKey(key)] = value }
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
		enqueue { prefs -> prefs[booleanPreferencesKey(key)] = value }
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
		enqueue { prefs -> prefs[longPreferencesKey(key)] = value }
	}

	/**
	 * Set float preference with key [key] to [value].
	 *
	 * @param key Preference key
	 * @param value New value
	 */
	fun setFloat(key: String, value: Float) {
		enqueue { prefs -> prefs[floatPreferencesKey(key)] = value }
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
		enqueue { prefs ->
			prefs.remove(stringPreferencesKey(key))
			prefs.remove(booleanPreferencesKey(key))
			prefs.remove(intPreferencesKey(key))
			prefs.remove(longPreferencesKey(key))
			prefs.remove(floatPreferencesKey(key))
		}
	}

	/**
	 * Remove all preference with given key prefix.
	 * Useful for clearing groups with common prefix.
	 * Needs to be used with caution as it may remove unwanted preferences.
	 */
	fun removeKeyByPrefix(prefix: String) {
		val keysToRemove = snapshot()
			.asMap()
			.keys
			.filter { it.name.startsWith(prefix) }
		if (keysToRemove.isEmpty()) return
		enqueue { prefs ->
			keysToRemove.forEach { key ->
				@Suppress("UNCHECKED_CAST")
				val typedKey = key as DataPreferences.Key<Any>
				prefs.remove(typedKey)
			}
		}
	}

	/**
	 * Remove all preferences
	 */
	fun clear() {
		enqueue { prefs -> prefs.clear() }
	}

	/**
	 * Apply all changes to preferences
	 */
	fun apply() {
		if (operations.isEmpty()) return
		val pending = operations.toList()
		operations.clear()
		LegacyPreferenceStore.edit(appContext, pending)
	}

	/**
	 * Apply changes and wait for completion.
	 */
	suspend fun commit() {
		if (operations.isEmpty()) return
		val pending = operations.toList()
		operations.clear()
		LegacyPreferenceStore.editSuspend(appContext, pending)
	}

	override fun edit(func: MutablePreferences.() -> Unit) {
		func(this)
		apply()
	}
}
