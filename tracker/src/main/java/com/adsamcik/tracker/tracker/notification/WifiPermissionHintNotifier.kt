package com.adsamcik.tracker.tracker.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.R

/**
 * Posts a gentle, throttled notification prompting the user to grant Wi‑Fi permissions
 * to improve indoor accuracy. Uses a long cooldown to avoid spamming.
 */
internal object WifiPermissionHintNotifier {
    private const val PREFS_NAME = "permission_hints"
    private const val KEY_LAST_SHOWN = "wifi_hint_last_shown"
    private const val KEY_SHOWN_COUNT = "wifi_hint_shown_count"

    // Show at most once per 7 days, and no more than 3 times total
    private const val COOLDOWN_MS: Long = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_SHOWS: Int = 3

    fun maybeNotify(context: Context) {
        // Respect notifications permission on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_SHOWN, 0L)
        val count = prefs.getInt(KEY_SHOWN_COUNT, 0)
        val now = Time.nowMillis

        if (count >= MAX_SHOWS) return
        if (now - last < COOLDOWN_MS) return

        val resources = context.resources
        val channelId = resources.getString(com.adsamcik.tracker.shared.base.R.string.channel_track_id)

        // Build explicit intent to onboarding without introducing a module dependency
        val launchIntent = Intent().apply {
            setClassName(
                context.packageName,
                "com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity"
            )
            // Matches OnboardingActivity.EXTRA_ONBOARDING_STEP and OnboardingStep.EnhancedFeatures.name
            putExtra("onboarding_step", "enhanced_features")
        }
        val contentIntent: PendingIntent? = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(launchIntent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val title = resources.getString(R.string.wifi_permission_hint_title)
        val text = resources.getString(R.string.wifi_permission_hint_text)

    // Reuse existing notification manager flow to post a single, low-priority notification
    val manager = TrackerNotificationManager(context, isUserInitiatedSession = true)
    manager.notify(NotificationCompat.Builder(context, channelId).also {
            it.setSmallIcon(R.drawable.ic_signals)
            it.setContentTitle(title)
            it.setContentText(text)
            it.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            it.setPriority(NotificationCompat.PRIORITY_LOW)
            it.setAutoCancel(true)
            it.setContentIntent(contentIntent)
        })

        prefs.edit()
            .putLong(KEY_LAST_SHOWN, now)
            .putInt(KEY_SHOWN_COUNT, count + 1)
            .apply()
    }
}
