package com.adsamcik.tracker.shared.preferences

import android.content.Context
import android.content.res.Resources
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.Preferences as DataPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore

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

    @Suppress("DEPRECATION")
    @Deprecated("Use observeString or suspend getString", ReplaceWith("getString(key, default)"))
    fun getStringRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): String {
        val key = getKey(keyRes)
        val default = resources.getString(defaultRes)
        return getString(key, default)
    }

    suspend fun fetchStringRes(@StringRes keyRes: Int): String? {
        val key = getKey(keyRes)
        return fetchString(key)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeString or suspend getString", ReplaceWith("getString(key)"))
    fun getStringResSync(@StringRes keyRes: Int): String? {
        val key = getKey(keyRes)
        return getStringSync(key)
    }

    suspend fun fetchString(key: String, default: String): String {
        return observeString(key, default).first()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeString or suspend getString", ReplaceWith("observeString(key, default).first()"))
    fun getString(key: String, default: String): String {
        return getStringSync(key) ?: default
    }

    @Deprecated("Use observeString or suspend getString")
    fun getStringSync(key: String): String? {
        return snapshot()[stringPreferencesKey(key)]
    }

    suspend fun fetchString(key: String): String? {
         return snapshot()[stringPreferencesKey(key)]
    }

    fun observeString(key: String, default: String): Flow<String> {
        return LegacyPreferenceStore.stringFlow(appContext, key, default)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeInt or suspend getInt", ReplaceWith("getInt(key, default)"))
    fun getIntRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Int {
        val key = getKey(keyRes)
        val default = resources.getInteger(defaultRes)
        return getInt(key, default)
    }

    suspend fun fetchIntRes(@StringRes keyRes: Int, @IntegerRes defaultRes: Int): Int {
        val key = getKey(keyRes)
        val default = resources.getInteger(defaultRes)
        return fetchInt(key, default)
    }

    @Suppress("DEPRECATION")
    fun getIntResValue(@StringRes keyRes: Int, default: Int): Int {
        val key = getKey(keyRes)
        return getInt(key, default)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeInt or suspend getInt", ReplaceWith("getIntResString(keyRes, defaultRes)"))
    fun getIntResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Int {
        val key = getKey(keyRes)
        val default = resources.getString(defaultRes).toInt()
        return getInt(key, default)
    }

    suspend fun fetchInt(key: String, default: Int = 0): Int {
        return observeInt(key, default).first()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeInt or suspend getInt", ReplaceWith("observeInt(key, default).first()"))
    fun getInt(key: String, default: Int = 0): Int {
        return getIntSync(key, default)
    }

    @Deprecated("Use observeInt or suspend getInt")
    fun getIntSync(key: String, default: Int = 0): Int {
        return snapshot()[intPreferencesKey(key)] ?: default
    }

    fun observeInt(key: String, default: Int = 0): Flow<Int> {
        return LegacyPreferenceStore.intFlow(appContext, key, default)
    }

    suspend fun fetchBooleanRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): Boolean {
        val default = resources.getString(defaultRes).toBoolean()
        return fetchBoolean(getKey(keyRes), default)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeBoolean or suspend getBoolean", ReplaceWith("getBooleanRes(keyRes, defaultRes)"))
    fun getBooleanRes(@StringRes keyRes: Int, @StringRes defaultRes: Int): Boolean {
        val default = resources.getString(defaultRes).toBoolean()
        return getBoolean(getKey(keyRes), default)
    }

    @Suppress("DEPRECATION")
    fun getBooleanRes(@StringRes keyRes: Int, default: Boolean): Boolean {
        val key = getKey(keyRes)
        return getBoolean(key, default)
    }

    suspend fun fetchBoolean(key: String, default: Boolean = false): Boolean {
        return observeBoolean(key, default).first()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeBoolean or suspend getBoolean", ReplaceWith("observeBoolean(key, default).first()"))
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return getBooleanSync(key, default)
    }

    @Deprecated("Use observeBoolean or suspend getBoolean")
    fun getBooleanSync(key: String, default: Boolean = false): Boolean {
        return snapshot()[booleanPreferencesKey(key)] ?: default
    }

    fun observeBoolean(key: String, default: Boolean = false): Flow<Boolean> {
        return LegacyPreferenceStore.booleanFlow(appContext, key, default)
    }

    suspend fun fetchLong(key: String, default: Long = 0L): Long {
        return observeLong(key, default).first()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeLong or suspend getLong", ReplaceWith("observeLong(key, default).first()"))
    fun getLong(key: String, default: Long = 0L): Long {
        return getLongSync(key, default)
    }

    @Deprecated("Use observeLong or suspend getLong")
    fun getLongSync(key: String, default: Long = 0L): Long {
        return snapshot()[longPreferencesKey(key)] ?: default
    }

    fun observeLong(key: String, default: Long = 0L): Flow<Long> {
        return LegacyPreferenceStore.longFlow(appContext, key, default)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeFloat or suspend getFloat", ReplaceWith("getFloatResString(keyRes, defaultRes)"))
    fun getFloatResString(@StringRes keyRes: Int, @StringRes defaultRes: Int): Float {
        val key = getKey(keyRes)
        val default = resources.getString(defaultRes).toFloat()
        return getFloat(key, default)
    }

    suspend fun fetchFloat(key: String, default: Float = Float.NaN): Float {
        return observeFloat(key, default).first()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use observeFloat or suspend getFloat", ReplaceWith("observeFloat(key, default).first()"))
    fun getFloat(key: String, default: Float = Float.NaN): Float {
        return getFloatSync(key, default)
    }

    @Deprecated("Use observeFloat or suspend getFloat")
    fun getFloatSync(key: String, default: Float = Float.NaN): Float {
        return snapshot()[floatPreferencesKey(key)] ?: default
    }

    fun observeFloat(key: String, default: Float = Float.NaN): Flow<Float> {
        return LegacyPreferenceStore.floatFlow(appContext, key, default)
    }

    @Suppress("DEPRECATION")
    fun getDouble(key: String, default: Double = Double.NaN): Double {
        val bits = getLong(key, default.toRawBits())
        return Double.fromBits(bits)
    }

    open fun edit(func: MutablePreferences.() -> Unit) {
        MutablePreferences(this).edit(func)
    }

    suspend fun editSuspend(func: MutablePreferences.() -> Unit) {
        val prefs = MutablePreferences(this)
        func(prefs)
        prefs.commit()
    }

    protected fun getKey(@StringRes keyRes: Int): String {
        return resources.getString(keyRes)
    }

    companion object {
        fun getPref(context: Context): Preferences = Preferences(context)
    }
}

