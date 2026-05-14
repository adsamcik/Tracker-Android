package com.adsamcik.tracker.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Debug-only ContentProvider that runs at app process start (before Application.onCreate)
 * and dynamically registers [DebugSeedBroadcastReceiver].
 *
 * Why dynamic? `am broadcast` automatically adds FLAG_RECEIVER_REGISTERED_ONLY (0x400000)
 * which filters out manifest-declared receivers. Dynamic registration bypasses that
 * restriction so adb-driven QA workflows can drive RESET_ONBOARDING / SEED_SESSIONS /
 * RESET_ALL_DATA from outside the app.
 *
 * This provider is declared in app/src/debug/AndroidManifest.xml only — RELEASE BUILDS DO NOT
 * BUILD THIS CLASS.
 */
@Suppress("UnspecifiedRegisterReceiverFlag")
class DebugInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx: Context = context ?: return false
        val receiver = DebugSeedBroadcastReceiver()
        val filter = IntentFilter().apply {
            addAction(DebugSeedBroadcastReceiver.ACTION_SEED_SESSIONS)
            addAction(DebugSeedBroadcastReceiver.ACTION_RESET_ONBOARDING)
            addAction(DebugSeedBroadcastReceiver.ACTION_RESET_ALL_DATA)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Debug-only: use EXPORTED so adb shell (UID 2000) can deliver via `am broadcast`.
                // RECEIVER_NOT_EXPORTED would block external delivery on Android 13+.
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            Log.i(TAG, "Debug seed receiver registered dynamically.")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to register debug seed receiver", error)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "DebugInitProvider"
    }
}
