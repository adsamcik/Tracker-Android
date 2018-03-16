package com.adsamcik.signalcollector.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.LaunchActivity
import com.adsamcik.signalcollector.enums.ResolvedActivity
import com.adsamcik.signalcollector.utility.Assist
import com.adsamcik.signalcollector.utility.Constants
import com.adsamcik.signalcollector.utility.Preferences

class ActivityWakerService : Service() {
    private var notificationManager: NotificationManager? = null
    private val NOTIFICATION_ID = 568465
    private var thread: Thread? = null

    private var activityInfo = ActivityService.lastActivity

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        instance = this

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        startForeground(NOTIFICATION_ID, updateNotification())

        ActivityService.requestAutoTracking(this, javaClass)

        thread = Thread {
            //Is not supposed to quit while, until service is stopped

            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep((500 + Preferences.getPref(this).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE * Constants.SECOND_IN_MILLISECONDS.toInt())).toLong())
                    val newActivityInfo = ActivityService.lastActivity
                    if (newActivityInfo != activityInfo) {
                        activityInfo = newActivityInfo
                        notificationManager!!.notify(NOTIFICATION_ID, updateNotification())
                    }
                } catch (e: InterruptedException) {
                    break
                }

            }

        }

        thread!!.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ActivityService.removeAutoTracking(this, javaClass)
        instance = null
        thread!!.interrupt()
    }

    private fun updateNotification(): Notification {
        val intent = Intent(this, LaunchActivity::class.java)
        val builder = NotificationCompat.Builder(this, getString(R.string.channel_track_id))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
                .setWhen(System.currentTimeMillis())  // the time stamp
                .setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
                .setColor(ContextCompat.getColor(this, R.color.color_accent))

        builder.setContentTitle(getString(R.string.notification_activity_watcher))
        builder.setContentText(getString(R.string.notification_activity_watcher_info, activityInfo.activityName, activityInfo.confidence))
        when (activityInfo.resolvedActivity) {
            ResolvedActivity.IN_VEHICLE -> builder.setSmallIcon(R.drawable.ic_directions_car_white_24dp)
            ResolvedActivity.ON_FOOT -> builder.setSmallIcon(R.drawable.ic_directions_walk_white_24dp)
            ResolvedActivity.STILL -> builder.setSmallIcon(R.drawable.ic_accessibility_white_24dp)
            ResolvedActivity.UNKNOWN -> builder.setSmallIcon(R.drawable.ic_help_white_24dp)
        }

        return builder.build()
    }

    companion object {
        private var instance: ActivityWakerService? = null

        /**
         * Pokes Activity Waker Service which checks if it should run
         *
         * @param context context
         */
        @Synchronized
        fun poke(context: Context) {
            val preference = Preferences.getPref(context).getBoolean(Preferences.PREF_ACTIVITY_WATCHER_ENABLED, Preferences.DEFAULT_ACTIVITY_WATCHER_ENABLED)
            poke(context, preference)
        }

        /**
         * Pokes Activity Waker Service which checks if it should run
         *
         * @param context context
         */
        @Synchronized
        fun poke(context: Context, state: Boolean) {
            if (state && !TrackerService.isRunning) {
                if (instance == null)
                    Assist.startServiceForeground(context, Intent(context, ActivityWakerService::class.java))
            } else if (instance != null) {
                instance!!.stopSelf()
            }
        }
    }
}
