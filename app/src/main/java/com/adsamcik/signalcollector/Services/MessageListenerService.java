package com.adsamcik.signalcollector.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;

import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Preferences;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.classes.UploadStats;
import com.adsamcik.signalcollector.play.PlayController;
import com.adsamcik.signalcollector.R;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.gson.Gson;

import java.util.Map;

public class MessageListenerService extends FirebaseMessagingService {
	private static final String TAG = "SignalsMessageListenerService";
	static int notificationIndex = 1;

	@Override
	public void onMessageReceived(RemoteMessage message) {
		final String TITLE = "title";
		final String MESSAGE = "message";
		final String TYPE = "type";

		Map<String, String> data = message.getData();

		String type = data.get(TYPE);
		if (type == null)
			return;

		switch (MessageType.values()[Integer.parseInt(type)]) {
			case UploadReport:
				UploadStats us = parseAndSaveUploadReport(data);
				break;
			case Notification:
				sendNotification(data.get(TITLE), data.get(MESSAGE));
				break;
			case Achievement:
				sendNotification(data.get(TITLE), data.get(MESSAGE));
				PlayController.gamesController.earnAchievement(data.get("achievement-id"));
				break;
		}
	}

	private UploadStats parseAndSaveUploadReport(Map<String, String> data) {
		final String WIFI = "wifi";
		final String NEW_WIFI = "newWifi";
		final String CELL = "cell";
		final String NEW_CELL = "newCell";
		final String NOISE = "noise";
		final String COLLECTIONS = "collections";
		final String NEW_LOCATIONS = "newLocations";
		final String SIZE = "uploadSize";

		int wifi = 0, cell = 0, noise = 0, collections = 0, newLocations = 0, newWifi = 0, newCell = 0;
		long uploadSize = 0;
		if (data.containsKey(WIFI))
			wifi = Integer.parseInt(data.get(WIFI));
		if (data.containsKey(NEW_WIFI))
			newWifi = Integer.parseInt(data.get(NEW_WIFI));
		if (data.containsKey(CELL))
			cell = Integer.parseInt(data.get(CELL));
		if (data.containsKey(NEW_CELL))
			newCell = Integer.parseInt(data.get(NEW_CELL));
		if (data.containsKey(NOISE))
			noise = Integer.parseInt(data.get(NOISE));
		if (data.containsKey(COLLECTIONS))
			collections = Integer.parseInt(data.get(COLLECTIONS));
		if (data.containsKey(NEW_LOCATIONS))
			newLocations = Integer.parseInt(data.get(NEW_LOCATIONS));
		if (data.containsKey(SIZE))
			uploadSize = Long.parseLong(data.get(SIZE));

		UploadStats us = new UploadStats(wifi, newWifi, cell, newCell, collections, newLocations, noise, uploadSize);
		DataStore.saveStringAppend(Preferences.RECENT_UPLOADS_FILE, new Gson().toJson(us));
		return us;
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
				.setContentIntent(pendingIntent)
				.setStyle(new Notification.BigTextStyle().bigText(message));

		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		notificationManager.notify(notificationIndex++, notificationBuilder.build());
	}

	public enum MessageType {
		UploadReport,
		Notification,
		Achievement
	}
}
