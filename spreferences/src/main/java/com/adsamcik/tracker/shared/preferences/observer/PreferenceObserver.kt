package com.adsamcik.tracker.shared.preferences.observer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntegerRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.adsamcik.tracker.shared.preferences.Preferences

/**
 * Preference observer observes changes on preferences and invokes proper listeners.
 */
@Suppress("WeakerAccess", "Unused", "TooManyFunctions")
@MainThread
object PreferenceObserver {
	private val intObserver = PreferenceListenerType<Int>()
	private val booleanObserver = PreferenceListenerType<Boolean>()
	private val longObserver = PreferenceListenerType<Long>()
	private val floatObserver = PreferenceListenerType<Float>()
	private val stringObserver = PreferenceListenerType<String>()

	private val onSharedPreferenceChangeListener =
		{ sharedPreferences: SharedPreferences, key: String? ->
			invokeAnyObserver(
				key,
				sharedPreferences
			)
		}

	/**
	 * Initializes preference observer.
	 */
	fun initialize(preferences: SharedPreferences) {
		preferences.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
	}


	private fun invokeAnyObserver(key: String?, preferences: SharedPreferences) {
		if (key == null) return
		val value = preferences.all[key] ?: return
		invokeAnyObserver(
			key,
			value
		)
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

	/**
	 * Observe string resource that represents integer for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeInt")
	fun observe(
		context: Context,
		@StringRes keyRes: Int,
		@StringRes defaultRes: Int,
		observer: Observer<Int>,
		owner: LifecycleOwner? = null
	) {
		Preferences
			.getPref(context)
			.getIntResString(keyRes, defaultRes)
			.run { observer.onChanged(this) }
		observe(
			context,
			intObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Observe integer resource for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeIntRes")
	fun observeIntRes(
		context: Context, @StringRes keyRes: Int,
		@IntegerRes defaultRes: Int,
		observer: Observer<Int>,
		owner: LifecycleOwner? = null
	) {
		Preferences.getPref(context).getIntRes(keyRes, defaultRes).run { observer.onChanged(this) }
		observe(
			context,
			intObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Observe string resource that represents 64bit integer for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeLong")
	fun observe(
		context: Context,
		@StringRes keyRes: Int,
		@StringRes defaultRes: Int,
		observer: Observer<Long>,
		owner: LifecycleOwner? = null
	) {
		Preferences.getPref(context).getLongResString(keyRes, defaultRes)
			.run { observer.onChanged(this) }
		observe(
			context,
			longObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Observe string resource that represents single-precision floating point for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeFloat")
	fun observe(
		context: Context,
		@StringRes keyRes: Int,
		@StringRes defaultRes: Int,
		observer: Observer<Float>,
		owner: LifecycleOwner? = null
	) {
		Preferences
			.getPref(context)
			.getFloatResString(keyRes, defaultRes)
			.run { observer.onChanged(this) }
		observe(
			context,
			floatObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Observe string resource that represents boolean for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeBoolean")
	fun observe(
		context: Context,
		@StringRes keyRes: Int,
		@StringRes defaultRes: Int,
		observer: Observer<Boolean>,
		owner: LifecycleOwner? = null
	) {
		Preferences
			.getPref(context)
			.getBooleanRes(keyRes, defaultRes)
			.run { observer.onChanged(this) }
		observe(
			context,
			booleanObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Observe string resource for change.
	 * The observer is first called with current value.
	 */
	@JvmName("observeString")
	fun observe(
		context: Context,
		@StringRes keyRes: Int,
		@StringRes defaultRes: Int,
		observer: Observer<String>,
		owner: LifecycleOwner? = null
	) {
		Preferences
			.getPref(context)
			.getStringRes(keyRes, defaultRes)
			.run { observer.onChanged(this) }
		observe(
			context,
			stringObserver,
			keyRes,
			observer,
			owner
		)
	}

	/**
	 * Removes registered integer observer.
	 */
	@JvmName("removeIntObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Int>) {
		removeObserver(
			context,
			intObserver,
			keyRes,
			observer
		)
	}

	/**
	 * Removes registered 64bit integer observer.
	 */
	@JvmName("removeLongObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Long>) {
		removeObserver(
			context,
			longObserver,
			keyRes,
			observer
		)
	}

	/**
	 * Removes registered single-precision floating point observer.
	 */
	@JvmName("removeFloatObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Float>) {
		removeObserver(
			context,
			floatObserver,
			keyRes,
			observer
		)
	}

	/**
	 * Removes registered boolean observer.
	 */
	@JvmName("removeBooleanObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<Boolean>) {
		removeObserver(
			context,
			booleanObserver,
			keyRes,
			observer
		)
	}

	/**
	 * Removes registered string observer.
	 */
	@JvmName("removeStringObserver")
	fun removeObserver(context: Context, @StringRes keyRes: Int, observer: Observer<String>) {
		removeObserver(
			context,
			stringObserver,
			keyRes,
			observer
		)
	}

	/**
	 * Removes registered observer.
	 */
	private fun <T> removeObserver(
		context: Context,
		type: PreferenceListenerType<T>,
		@StringRes keyRes: Int,
		observer: Observer<T>
	) {
		val key = context.getString(keyRes)
		type.removeObserver(key, observer)
	}

	private fun <T> observe(
		context: Context,
		type: PreferenceListenerType<T>, @StringRes keyRes: Int,
		observer: Observer<T>,
		owner: LifecycleOwner? = null
	) {
		val key = context.getString(keyRes)
		if (owner != null) type.observe(key, observer, owner) else type.observe(key, observer)
	}

}

