package com.adsamcik.signalcollector.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.LaunchActivity
import com.adsamcik.signalcollector.activities.UploadReportsActivity
import com.adsamcik.signalcollector.data.Challenge
import com.adsamcik.signalcollector.data.UploadStats
import com.adsamcik.signalcollector.file.DataStore
import com.adsamcik.signalcollector.notifications.Notifications
import com.adsamcik.signalcollector.utility.ChallengeManager
import com.adsamcik.signalcollector.utility.Preferences
import com.crashlytics.android.Crashlytics
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessageListenerService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {

        val sp = Preferences.getPref(this)

        val data = message.data

        val type = data[TYPE]

        if(type == null) {
            Crashlytics.logException(Throwable("No TYPE defined!!"))
            return
        }

        val messageType: MessageType

        try {
            messageType = MessageType.valueOf(type)
        } catch (e: Exception) {
            Crashlytics.logException(e)
            return
        }

        when (messageType) {
            MessageListenerService.MessageType.UploadReport -> {
                DataStore.removeOldRecentUploads(this)
                val us = parseAndSaveUploadReport(applicationContext, message.sentTime, data)
                if (!sp.contains(Preferences.PREF_OLDEST_RECENT_UPLOAD))
                    sp.edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, us.time).apply()
                val resultIntent = Intent(this, UploadReportsActivity::class.java)

                if (Preferences.getPref(this).getBoolean(Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED, true)) {
                    val r = resources
                    sendNotification(MessageType.UploadReport, r.getString(R.string.new_upload_summary), us.generateNotificationText(resources), PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT), message.sentTime)
                }
            }
            MessageListenerService.MessageType.Notification -> sendNotification(MessageType.Notification, data[TITLE]!!, data[MESSAGE]!!, null, message.sentTime)
            MessageListenerService.MessageType.ChallengeReport -> {
                val isDone = java.lang.Boolean.parseBoolean(data["isDone"])
                if (isDone) {
                    val challengeType: Challenge.ChallengeType
                    try {
                        challengeType = Challenge.ChallengeType.valueOf(data["challengeType"]!!)
                    } catch (e: Exception) {
                        sendNotification(MessageType.ChallengeReport, getString(R.string.notification_challenge_unknown_title), getString(R.string.notification_challenge_unknown_description), null, message.sentTime)
                        return
                    }

                    ChallengeManager.getChallenges(this, false, { source, challenges ->
                        if (source.success && challenges != null) {
                            for (challenge in challenges) {
                                if (challenge.type == challengeType) {
                                    challenge.setDone()
                                    challenge.generateTexts(this)
                                    sendNotification(MessageType.ChallengeReport,
                                            getString(R.string.notification_challenge_done_title, challenge.title),
                                            getString(R.string.notification_challenge_done_description, challenge.title), null,
                                            message.sentTime)
                                    break
                                }
                            }
                            //todo It should generate texts every time when loaded to properly handle localization changes
                            ChallengeManager.saveChallenges(this, challenges)
                        }
                    })
                }

            }
        }
    }

    /**
     * Create and show notification
     *
     * @param title         title
     * @param message       message
     * @param pendingIntent intent if special action is wanted
     */
    private fun sendNotification(messageType: MessageType, title: String, message: String, pendingIntent: PendingIntent?, time: Long) {
        var pIntent = pendingIntent
        val intent = Intent(this, LaunchActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (pIntent == null)
            pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT)

        @StringRes val channelId: Int = when (messageType) {
            MessageListenerService.MessageType.UploadReport -> R.string.channel_upload_id
            MessageListenerService.MessageType.ChallengeReport -> R.string.channel_challenges_id
            MessageListenerService.MessageType.Notification -> R.string.channel_other_id
        }

        val notiColor = ContextCompat.getColor(applicationContext, R.color.color_accent)

        val notiBuilder = NotificationCompat.Builder(this, getString(channelId))
                .setSmallIcon(R.drawable.ic_signals)
                .setTicker(title)
                .setColor(notiColor)
                .setLights(notiColor, 2000, 5000)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pIntent)
                .setWhen(time)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.notify(Notifications.uniqueNotificationId(), notiBuilder.build())
    }

    enum class MessageType {
        Notification,
        UploadReport,
        ChallengeReport
    }

    companion object {
        private const val TITLE = "title"
        private const val MESSAGE = "message"
        private const val TYPE = "type"

        private const val TAG = "SignalsMessageService"

        const val WIFI = "wifi"
        const val NEW_WIFI = "newWifi"
        const val CELL = "cell"
        const val NEW_CELL = "newCell"
        const val COLLECTIONS = "collections"
        const val NEW_LOCATIONS = "newLocations"
        const val UPLOAD_SIZE = "uploadSize"

        fun parseAndSaveUploadReport(context: Context, time: Long, data: Map<String, String>): UploadStats {
            var wifi = 0
            var cell = 0
            var collections = 0
            var newLocations = 0
            var newWifi = 0
            var newCell = 0
            var uploadSize: Long = 0
            if (data.containsKey(WIFI))
                wifi = Integer.parseInt(data[WIFI])
            if (data.containsKey(NEW_WIFI))
                newWifi = Integer.parseInt(data[NEW_WIFI])
            if (data.containsKey(CELL))
                cell = Integer.parseInt(data[CELL])
            if (data.containsKey(NEW_CELL))
                newCell = Integer.parseInt(data[NEW_CELL])
            if (data.containsKey(COLLECTIONS))
                collections = Integer.parseInt(data[COLLECTIONS])
            if (data.containsKey(NEW_LOCATIONS))
                newLocations = Integer.parseInt(data[NEW_LOCATIONS])
            if (data.containsKey(UPLOAD_SIZE))
                uploadSize = java.lang.Long.parseLong(data[UPLOAD_SIZE])

            val us = UploadStats(time, wifi, newWifi, cell, newCell, collections, newLocations, uploadSize)
            DataStore.saveAppendableJsonArray(context, DataStore.RECENT_UPLOADS_FILE, us, UploadStats::class.java, true)

            Preferences.checkStatsDay(context)
            val sp = Preferences.getPref(context)
            sp.edit().putLong(Preferences.PREF_STATS_UPLOADED, sp.getLong(Preferences.PREF_STATS_UPLOADED, 0) + uploadSize).apply()
            return us
        }
    }
}
