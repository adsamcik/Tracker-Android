package com.adsamcik.signalcollector.preference.listener

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.preference.Preferences

@Suppress("WeakerAccess", "UNUSED")
object PreferenceListener {
	private val intListener = PreferenceListenerType<Int>()
	private val boolListener = PreferenceListenerType<Boolean>()
	private val longListener = PreferenceListenerType<Long>()
	private val floatListener = PreferenceListenerType<Float>()
	private val stringListener = PreferenceListenerType<String>()

	private var isInitialized = false

	private val onSharedPreferenceChangeListener = { sharedPreferences: SharedPreferences, key: String -> invokeAnyListener(key, sharedPreferences) }

	fun initialize(preferences: SharedPreferences) {
		preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}

	private fun invokeAnyListener(key: String, preferences: SharedPreferences) {
		val value = preferences.all[key] ?: return
		invokeAnyListener(key, value)
	}

	private fun invokeAnyListener(key: String, value: Any) {
		when (value) {
			is String -> stringListener.invoke(key, value)
			is Boolean -> boolListener.invoke(key, value)
			is Int -> intListener.invoke(key, value)
			is Long -> longListener.invoke(key, value)
			is Float -> floatListener.invoke(key, value)
			else -> throw NotImplementedError("${value.javaClass.name} not supported!")
		}
	}

	@JvmName("observeInt")
	fun observe(context: Context, @StringRes keyRes: Int, listener: Observer<Int>, @StringRes defaultRes: Int, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getIntResString(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, intListener, keyRes, listener, owner)
	}

	@JvmName("observeIntRes")
	fun observeIntRes(context: Context, @StringRes keyRes: Int, listener: Observer<Int>, @IntegerRes defaultRes: Int, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getIntRes(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, intListener, keyRes, listener, owner)
	}

	@JvmName("observeLong")
	fun observe(context: Context, @StringRes keyRes: Int, listener: Observer<Long>, @StringRes defaultRes: Int, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getLongResString(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, longListener, keyRes, listener, owner)
	}

	@JvmName("observeFloat")
	fun observe(context: Context, @StringRes keyRes: Int, listener: Observer<Float>, @StringRes defaultRes: Int, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getFloatResString(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, floatListener, keyRes, listener, owner)
	}

	@JvmName("observeBoolean")
	fun observe(context: Context, @StringRes keyRes: Int, owner: LifecycleOwner? = null, @StringRes defaultRes: Int, listener: Observer<Boolean>) {
		Preferences.getPref(context).getBooleanRes(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, boolListener, keyRes, listener, owner)
	}

	@JvmName("observeString")
	fun observe(context: Context, @StringRes keyRes: Int, listener: Observer<String>, @StringRes defaultRes: Int, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getStringRes(keyRes, defaultRes).run { listener.onChanged(this) }
		observe(context, stringListener, keyRes, listener, owner)
	}

	@JvmName("removeIntObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, listener: Observer<Int>) {
		removeObserver(context, intListener, keyRes, listener)
	}

	@JvmName("removeLongObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, listener: Observer<Long>) {
		removeObserver(context, longListener, keyRes, listener)
	}

	@JvmName("removeFloatObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, listener: Observer<Float>) {
		removeObserver(context, floatListener, keyRes, listener)
	}

	@JvmName("removeBooleanObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, listener: Observer<Boolean>) {
		removeObserver(context, boolListener, keyRes, listener)
	}

	@JvmName("removeStringObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, listener: Observer<String>) {
		removeObserver(context, stringListener, keyRes, listener)
	}

	private fun <T> removeObserver(context: Context, type: PreferenceListenerType<T>, @StringRes keyRes: Int, observer: Observer<T>) {
		val key = context.getString(keyRes)
		type.removeObserver(key, observer)
	}

	private fun <T> observe(context: Context, type: PreferenceListenerType<T>, @StringRes keyRes: Int, observer: Observer<T>, owner: LifecycleOwner? = null) {
		val key = context.getString(keyRes)
		if (owner != null) type.observe(key, observer, owner) else type.observe(key, observer)
	}

}