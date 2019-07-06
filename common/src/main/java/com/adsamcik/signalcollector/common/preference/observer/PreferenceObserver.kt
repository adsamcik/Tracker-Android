package com.adsamcik.signalcollector.common.preference.observer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntegerRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.common.preference.Preferences

@Suppress("WeakerAccess", "UNUSED")
@MainThread
object PreferenceObserver {
	private val intObserver = PreferenceListenerType<Int>()
	private val booleanObserver = PreferenceListenerType<Boolean>()
	private val longObserver = PreferenceListenerType<Long>()
	private val floatObserver = PreferenceListenerType<Float>()
	private val stringObserver = PreferenceListenerType<String>()

	private var isInitialized = false

	private val onSharedPreferenceChangeListener = { sharedPreferences: SharedPreferences, key: String -> invokeAnyObserver(key, sharedPreferences) }

	fun initialize(preferences: SharedPreferences) {
		preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}

	private fun invokeAnyObserver(key: String, preferences: SharedPreferences) {
		val value = preferences.all[key] ?: return
		invokeAnyObserver(key, value)
	}

	private fun invokeAnyObserver(key: String, value: Any) {
		when (value) {
			is String -> stringObserver.invoke(key, value)
			is Boolean -> booleanObserver.invoke(key, value)
			is Int -> intObserver.invoke(key, value)
			is Long -> longObserver.invoke(key, value)
			is Float -> floatObserver.invoke(key, value)
			else -> throw NotImplementedError("${value.javaClass.name} not supported!")
		}
	}

	@JvmName("observeInt")
	fun observe(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int, observer: Observer<Int>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getIntResString(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, intObserver, keyRes, observer, owner)
	}

	@JvmName("observeIntRes")
	fun observeIntRes(context: Context, @StringRes keyRes: Int, @IntegerRes defaultRes: Int, observer: Observer<Int>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getIntRes(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, intObserver, keyRes, observer, owner)
	}

	@JvmName("observeLong")
	fun observe(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int, observer: Observer<Long>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getLongResString(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, longObserver, keyRes, observer, owner)
	}

	@JvmName("observeFloat")
	fun observe(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int, observer: Observer<Float>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getFloatResString(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, floatObserver, keyRes, observer, owner)
	}

	@JvmName("observeBoolean")
	fun observe(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int, observer: Observer<Boolean>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getBooleanRes(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, booleanObserver, keyRes, observer, owner)
	}

	@JvmName("observeString")
	fun observe(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int, observer: Observer<String>, owner: LifecycleOwner? = null) {
		Preferences.getPref(context).getStringRes(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(context, stringObserver, keyRes, observer, owner)
	}

	@JvmName("removeIntObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Int>) {
		removeObserver(context, intObserver, keyRes, observer)
	}

	@JvmName("removeLongObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Long>) {
		removeObserver(context, longObserver, keyRes, observer)
	}

	@JvmName("removeFloatObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Float>) {
		removeObserver(context, floatObserver, keyRes, observer)
	}

	@JvmName("removeBooleanObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Boolean>) {
		removeObserver(context, booleanObserver, keyRes, observer)
	}

	@JvmName("removeStringObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<String>) {
		removeObserver(context, stringObserver, keyRes, observer)
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