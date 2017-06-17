package com.adsamcik.signalcollector.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.MalformedJsonException;

import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.activities.RecentUploadsActivity;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.R;
import com.google.firebase.crash.FirebaseCrash;
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

		Context context = getApplicationContext();
		SharedPreferences sp = Preferences.get(context);

		DataStore.setContext(context);

		Map<String, String> data = message.getData();

		String type = data.get(TYPE);
		if (type == null)
			return;

		int typeInt = Integer.parseInt(type);
		if (MessageType.values().length > typeInt) {
			switch (MessageType.values()[typeInt]) {
				case UploadReport:
					DataStore.removeOldRecentUploads();
					UploadStats us = parseAndSaveUploadReport(getApplicationContext(), message.getSentTime(), data);
					if (!sp.contains(Preferences.PREF_OLDEST_RECENT_UPLOAD))
						sp.edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, us.time).apply();
					Intent resultIntent = new Intent(this, RecentUploadsActivity.class);

					if (Preferences.get(this).getBoolean(Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED, true)) {
						Resources r = getResources();
						sendNotification(MessageType.UploadReport, r.getString(R.string.new_upload_summary), us.generateNotificationText(getResources()), PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT), message.getSentTime());
					}
					break;
				case Notification:
					sendNotification(MessageType.Notification, data.get(TITLE), data.get(MESSAGE), null, message.getSentTime());
					break;
			}
		}
	}

	public static UploadStats parseAndSaveUploadReport(@NonNull Context context, final long time, final Map<String, String> data) {
		final String WIFI = "wifi";
		final String NEW_WIFI = "newWifi";
		final String CELL = "cell";
		final String NEW_CELL = "newCell";
		final String NOISE = "noise";
		final String NEW_NOISE_LOCATIONS = "newNoiseLocations";
		final String COLLECTIONS = "collections";
		final String NEW_LOCATIONS = "newLocations";
		final String SIZE = "uploadSize";

		int wifi = 0, cell = 0, noise = 0, collections = 0, newLocations = 0, newWifi = 0, newCell = 0, newNoiseLocations = 0;
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
		if (data.containsKey(NEW_NOISE_LOCATIONS))
			newNoiseLocations = Integer.parseInt(data.get(NEW_NOISE_LOCATIONS));
		if (data.containsKey(SIZE))
			uploadSize = Long.parseLong(data.get(SIZE));

		UploadStats us = new UploadStats(time, wifi, newWifi, cell, newCell, collections, newLocations, noise, uploadSize, newNoiseLocations);
		try {
			DataStore.saveJsonArrayAppend(DataStore.RECENT_UPLOADS_FILE, new Gson().toJson(us));
		} catch (MalformedJsonException e) {
			FirebaseCrash.report(e);
		}

		Preferences.checkStatsDay(context);
		SharedPreferences sp = Preferences.get(context);
		sp.edit().putLong(Preferences.PREF_STATS_UPLOADED, sp.getLong(Preferences.PREF_STATS_UPLOADED, 0) + uploadSize).apply();
		return us;
	}

	/**
	 * Create and show notification
	 *
	 * @param title         title
	 * @param message       message
	 * @param pendingIntent intent if special action is wanted
	 */
	private void sendNotification(MessageType messageType, @NonNull final String title, @NonNull final String message, @Nullable PendingIntent pendingIntent, long time) {
		Intent intent = new Intent(this, MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		if (pendingIntent == null)
			pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

		@StringRes int channelId = messageType == MessageType.UploadReport ? R.string.channel_upload_id : R.string.channel_other_id;

		int notiColor = ContextCompat.getColor(getApplicationContext(), R.color.colorPrimary);

		NotificationCompat.Builder notiBuilder = new NotificationCompat.Builder(this, getString(channelId))
				.setSmallIcon(R.drawable.ic_signals_launcher)
				.setTicker(title)
				.setColor(notiColor)
				.setLights(notiColor, 2000, 5000)
				.setContentTitle(title)
				.setContentText(message)
				.setContentIntent(pendingIntent)
				.setWhen(time)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(message));

		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		notificationManager.notify(notificationIndex++, notiBuilder.build());
	}

	public enum MessageType {
		Notification,
		UploadReport
	}
}
