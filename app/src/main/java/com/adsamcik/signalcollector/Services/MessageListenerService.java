package com.adsamcik.signalcollector.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.R;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class MessageListenerService extends FirebaseMessagingService {
	private static final String TAG = "MessageListenerService";
	static int notificationIndex = 1;

	@Override
	public void onMessageReceived(RemoteMessage message){

		Map<String, String> data = message.getData();

		String type = message.getMessageType();
		if(type == null)
			return;

		String title = data.get("title");
		String msg = message.getNotification().getBody();

		message.getNotification().notify();
		switch(MessageType.values()[Integer.parseInt(type)]) {
			case Notification:
				sendNotification(title, msg);
				break;
			case Achievement:
				sendNotification(title, msg);
				PlayController.gamesController.earnAchievement(data.get("achievement-id"));
				break;
		}
		Log.d(TAG, title + msg);
	}

	/**
	 * Create and show a simple notification containing the received GCM value.
	 *
	 * @param message GCM value received.
	 */
	private void sendNotification(String title, String message) {
		Intent intent = new Intent(this, MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

		Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
		Notification.Builder notificationBuilder = new Notification.Builder(this)
				.setSmallIcon(R.drawable.ic_signals_notification)
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
