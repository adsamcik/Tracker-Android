package com.adsamcik.signalcollector.utility;

import android.app.NotificationChannel;
import android.content.Context;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.annotation.StringRes;

import com.adsamcik.signalcollector.R;

@RequiresApi(26)
public class NotificationTools {

	public static void prepareChannels(@NonNull Context context) {
		createChannel(context, R.string.channel_track_id, R.string.channel_track_name, R.string.channel_track_description, true, android.app.NotificationManager.IMPORTANCE_LOW);
		createChannel(context, R.string.channel_upload_id, R.string.channel_upload_name, R.string.channel_upload_description, false, android.app.NotificationManager.IMPORTANCE_LOW);
		createChannel(context, R.string.channel_other_id, R.string.channel_other_name, R.string.channel_other_description, true, android.app.NotificationManager.IMPORTANCE_LOW);
	}

	private static void createChannel(@NonNull Context context, @StringRes int idId, @StringRes int nameId, @StringRes int descriptionId, boolean useVibration, int importance) {
		android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel mChannel = new NotificationChannel(context.getString(idId), context.getString(nameId), importance);
// Configure the notification channel.
		mChannel.setDescription(context.getString(descriptionId));
		mChannel.enableLights(true);
		mChannel.setLightColor(Color.GREEN);
		mChannel.enableVibration(useVibration);
		mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
		assert mNotificationManager != null;
		mNotificationManager.createNotificationChannel(mChannel);
	}
}
