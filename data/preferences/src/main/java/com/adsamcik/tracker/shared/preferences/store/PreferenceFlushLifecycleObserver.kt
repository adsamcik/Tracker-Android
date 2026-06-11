package com.adsamcik.tracker.shared.preferences.store

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Flushes pending [LegacyPreferenceStore] DataStore writes when the app
 * moves to the background ([ON_STOP][androidx.lifecycle.Lifecycle.Event.ON_STOP]).
 *
 * Register on [androidx.lifecycle.ProcessLifecycleOwner] during Application.onCreate:
 * ```
 * ProcessLifecycleOwner.get().lifecycle.addObserver(
 *     PreferenceFlushLifecycleObserver(this, appScope)
 * )
 * ```
 */
class PreferenceFlushLifecycleObserver(
    private val context: Context,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            LegacyPreferenceStore.flush(context)
        }
    }
}
