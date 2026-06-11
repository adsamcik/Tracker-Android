package com.adsamcik.tracker.shared.preferences.flow

import android.content.Context
import androidx.annotation.IntegerRes
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.preferences.store.LegacyPreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Flow-based accessors for callers that need Flow/StateFlow-friendly preference streams.
 * Values are backed by DataStore and emit immediately with current state, then on every change.
 */
object PreferenceFlows {

    fun boolean(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int): Flow<Boolean> {
        val key = context.getString(keyRes)
        val default = context.getString(defaultRes).toBoolean()
        return LegacyPreferenceStore.booleanFlow(context, key, default)
    }

    fun boolean(context: Context, key: String, default: Boolean): Flow<Boolean> =
        LegacyPreferenceStore.booleanFlow(context, key, default)

    fun int(context: Context, @StringRes keyRes: Int, @IntegerRes defaultRes: Int): Flow<Int> {
        val key = context.getString(keyRes)
        val default = context.resources.getInteger(defaultRes)
        return LegacyPreferenceStore.intFlow(context, key, default)
    }

    fun int(context: Context, key: String, default: Int): Flow<Int> =
        LegacyPreferenceStore.intFlow(context, key, default)

    fun intFromString(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int): Flow<Int> {
        val key = context.getString(keyRes)
        val defaultString = context.getString(defaultRes)
        val default = defaultString.toIntOrNull() ?: 0
        return LegacyPreferenceStore.stringOrIntFlow(context, key, defaultString)
            .map { it.toIntOrNull() ?: default }
            .distinctUntilChanged()
    }

    fun intFromString(context: Context, key: String, default: Int): Flow<Int> {
        val defaultString = default.toString()
        return LegacyPreferenceStore.stringOrIntFlow(context, key, defaultString)
            .map { it.toIntOrNull() ?: default }
            .distinctUntilChanged()
    }

    fun string(context: Context, @StringRes keyRes: Int, @StringRes defaultRes: Int): Flow<String> {
        val key = context.getString(keyRes)
        val default = context.getString(defaultRes)
        return LegacyPreferenceStore.stringFlow(context, key, default)
    }
}
