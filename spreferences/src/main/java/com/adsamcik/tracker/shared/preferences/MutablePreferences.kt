package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes

@Suppress("UNUSED", "PRIVATE", "TooManyFunctions")
class MutablePreferences : Preferences {
	private val editor: SharedPreferences.Editor = sharedPreferences.edit()

	constructor(context: Context) : super(context)
	constructor(preferences: Preferences) : super(preferences)

	fun setString(@StringRes keyRes: Int, value: String) {
		val key = getKey(keyRes)
		setString(key, value)
	}

	fun setString(key: String, value: String) {
		editor.putString(key, value)
	}

	fun setInt(@StringRes keyRes: Int, value: Int) {
		val key = getKey(keyRes)
		setInt(key, value)
	}

	fun setInt(key: String, value: Int) {
		editor.putInt(key, value)
	}

	fun setBoolean(@StringRes keyRes: Int, value: Boolean) {
		val key = getKey(keyRes)
		setBoolean(key, value)
	}

	fun setBoolean(key: String, value: Boolean) {
		editor.putBoolean(key, value)
	}

	fun setLong(@StringRes keyRes: Int, value: Long) {
		val key = getKey(keyRes)
		setLong(key, value)
	}

	fun setLong(key: String, value: Long) {
		editor.putLong(key, value)
	}

	fun setFloat(key: String, value: Float) {
		editor.putFloat(key, value)
	}

	fun setDouble(key: String, value: Double) {
		setLong(key, value.toRawBits())
	}

	fun remove(@StringRes keyRes: Int) {
		val key = getKey(keyRes)
		remove(key)
	}

	fun remove(key: String) {
		editor.remove(key)
	}

	fun removeKeyByPrefix(prefix: String) {
		sharedPreferences.all
				.filter { it.key.startsWith(prefix) }
				.forEach { remove(it.key) }
	}

	fun clear() {
		editor.clear()
	}

	fun apply() {
		editor.apply()
	}

	override fun edit(func: MutablePreferences.() -> Unit) {
		func(this)
		apply()
	}
}
