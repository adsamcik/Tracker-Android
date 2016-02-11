package com.adsamcik.signalcollector.Services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;

import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Play.PlayController;
import com.adsamcik.signalcollector.R;
import com.google.android.gms.gcm.GcmListenerService;

public class MessageListenerService extends GcmListenerService {
    private static final String TAG = "MyGcmListenerService";
    static int notificationIndex = 1;

    /**
     * Called when message is received.
     *
     * @param from SenderID of the sender.
     * @param data Data bundle containing message data as key/value pairs.
     *             For Set of keys use data.keySet().
     */
    // [START receive_message]
    @Override
    public void onMessageReceived(String from, Bundle data) {
        if (PlayController.gapiGamesClient == null)
            return;

        String type = data.getString("type");
        if (type == null)
            return;

        String message = data.getString("message");
        String title = data.getString("title");

        switch (MessageType.values()[Integer.parseInt(type)]) {
            case Notification:
                sendNotification(title, message);
                break;
            case Achievement:
                String id = data.getString("id");
                sendAchievementNotification(title, message, id);
                PlayController.gamesController.earnAchievement(id);
                break;
        }
        //Log.d(TAG, "From: " + from);
        //Log.d(TAG, "Message: " + message);
    }

    private void sendAchievementNotification(String title, String message, String id) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        Notification.Builder notificationBuilder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_icon)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(notificationIndex++, notificationBuilder.build());
    }

    /**
     * Create and show a simple notification containing the received GCM message.
     *
     * @param message GCM message received.
     */
    private void sendNotification(String title, String message) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        Notification.Builder notificationBuilder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_icon)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(notificationIndex++, notificationBuilder.build());
    }

    public enum MessageType {
        Notification,
        Achievement
    }
}
