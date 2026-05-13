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
import com.adsamcik.tracker.tracker.data.store.permissionHintsProtoDataStore
import kotlinx.coroutines.flow.first

/**
 * Posts a gentle, throttled notification prompting the user to grant Wi‑Fi permissions
 * to improve indoor accuracy. Uses a long cooldown to avoid spamming.
 * 
 * Uses Proto DataStore for persistence (Plan 2 migration).
 */
internal object WifiPermissionHintNotifier {
    // Show at most once per 7 days, and no more than 3 times total
    private const val COOLDOWN_MS: Long = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_SHOWS: Int = 3

    suspend fun maybeNotify(context: Context) {
        // Respect notifications permission on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val dataStore = context.permissionHintsProtoDataStore
        val hints = dataStore.data.first()
        val now = Time.nowMillis

        if (hints.wifiHintShownCount >= MAX_SHOWS) return
        if (now - hints.wifiHintLastShown < COOLDOWN_MS) return

        TrackerNotificationChannels.ensureTrackingChannel(context)

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
            it.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals)
            it.setContentTitle(title)
            it.setContentText(text)
            it.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            it.setPriority(NotificationCompat.PRIORITY_LOW)
            it.setAutoCancel(true)
            it.setContentIntent(contentIntent)
        })

        dataStore.updateData { current ->
            current.toBuilder()
                .setWifiHintLastShown(now)
                .setWifiHintShownCount(current.wifiHintShownCount + 1)
                .build()
        }
    }
}
