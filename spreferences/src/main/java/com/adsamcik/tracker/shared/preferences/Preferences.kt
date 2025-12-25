package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.Preferences as DataPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.extension.getPreferredLengthSystem

/**
 * Legacy synchronous preference accessor backed by DataStore.
 * Provides the historical API surface while underlying storage has migrated
 * away from SharedPreferences.
 */
@Suppress("Unused", "TooManyFunctions")
open class Preferences {
    protected val resources: Resources
    protected val appContext: Context

    constructor(context: Context) {
        appContext = context.applicationContext
        resources = context.resources
    }

    constructor(preferences: Preferences) : this(preferences.appContext)

    protected fun snapshot(): DataPreferences = LegacyPreferenceStore.snapshot(appContext)

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
        return getString(key) ?: default
    }

    fun getString(key: String): String? {
        return snapshot()[stringPreferencesKey(key)]
    }

    fun getIntRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Int {
        val key = getKey(keyRes)
        val default = resources.getInteger(defaultRes)
        return getInt(key, default)
    }

    fun getIntResValue(@StringRes keyRes: Int, default: Int): Int {
        val key = getKey(keyRes)
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
        return snapshot()[intPreferencesKey(key)] ?: default
    }

    fun getBooleanRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): Boolean {
        val default = resources.getString(defaultRes).toBoolean()
        return getBoolean(getKey(keyRes), default)
    }

    fun getBooleanRes(@StringRes keyRes: Int, default: Boolean): Boolean {
        val key = getKey(keyRes)
        return getBoolean(key, default)
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return snapshot()[booleanPreferencesKey(key)] ?: default
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
        return snapshot()[longPreferencesKey(key)] ?: default
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
        return snapshot()[floatPreferencesKey(key)] ?: default
    }

    fun getDouble(key: String, default: Double = Double.NaN): Double {
        val bits = getLong(key, default.toRawBits())
        return Double.fromBits(bits)
    }

    fun getPreferredLengthSystem(context: Context, sessionActivity: SessionActivity?): LengthSystem {
        val settings = TrackerSettingsQuick.snapshot(context)
        val base = settings.lengthSystem
        return if (!settings.autoUnitSwitch || sessionActivity == null) {
            base
        } else {
            sessionActivity.getPreferredLengthSystem() ?: base
        }
    }

    open fun edit(func: MutablePreferences.() -> Unit) {
        MutablePreferences(this).edit(func)
    }

    protected fun getKey(@StringRes keyRes: Int): String {
        return resources.getString(keyRes)
    }

    companion object {
        private var preferences: Preferences? = null

        @Synchronized
        fun getPref(context: Context): Preferences {
            val existing = preferences
            if (existing != null) return existing
            return Preferences(context).also { preferences = it }
        }
    }
}

