package com.adsamcik.signalcollector.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.preference.listener.PreferenceListener

@Suppress("UNUSED", "PRIVATE")
class MutablePreferences : Preferences {
	private val editor: SharedPreferences.Editor = sharedPreferences.edit()

	private val changedValues = mutableListOf<Pair<String, Any>>()

	constructor(context: Context) : super(context)
	constructor(preferences: Preferences) : super(preferences)

	fun setString(@StringRes keyRes: Int, value: String) {
		val key = getKey(keyRes)
		setString(key, value)
	}

	fun setString(key: String, value: String) {
		editor.putString(key, value)
		changedValues.add(key to value)
	}

	fun setInt(@StringRes keyRes: Int, value: Int) {
		val key = getKey(keyRes)
		setInt(key, value)
	}

	fun setInt(key: String, value: Int) {
		editor.putInt(key, value)
		changedValues.add(key to value)
	}

	fun setBoolean(@StringRes keyRes: Int, value: Boolean) {
		val key = getKey(keyRes)
		setBoolean(key, value)
	}

	fun setBoolean(key: String, value: Boolean) {
		editor.putBoolean(key, value)
		changedValues.add(key to value)
	}

	fun setLong(@StringRes keyRes: Int, value: Long) {
		val key = getKey(keyRes)
		setLong(key, value)
	}

	fun setLong(key: String, value: Long) {
		editor.putLong(key, value)
		changedValues.add(key to value)
	}

	fun remove(@StringRes keyRes: Int) {
		val key = getKey(keyRes)
		remove(key)
	}

	fun remove(key: String) {
		editor.remove(key)
		changedValues.indexOfFirst { it.first == key }.also {
			if (it >= 0)
				changedValues.removeAt(it)
		}
	}

	fun clear() {
		editor.clear()
		changedValues.clear()
	}

	fun apply() {
		editor.apply()
		changedValues.forEach { PreferenceListener.invokeAnyListener(it.first, it.second) }
		changedValues.clear()
	}

	override fun edit(func: MutablePreferences.() -> Unit) {
		func(this)
		apply()
	}
}