package com.adsamcik.signalcollector.utility;

import android.app.NotificationChannel;
import android.content.Context;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.annotation.StringRes;

import com.adsamcik.signalcollector.R;

@RequiresApi(26)
public class NotificationManager {

	public static void prepareChannels(@NonNull Context context) {
		createChannel(context, R.string.channel_track_id, R.string.channel_track_name, R.string.channel_track_description, true);
		createChannel(context, R.string.channel_upload_id, R.string.channel_upload_name, R.string.channel_upload_description, false);
		createChannel(context, R.string.channel_other_id, R.string.channel_other_name, R.string.channel_other_description, true);
	}

	private static void createChannel(@NonNull Context context, @StringRes int idId, @StringRes int nameId, @StringRes int descriptionId, boolean useVibration) {
		android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel mChannel = new NotificationChannel(context.getString(idId), context.getString(nameId), android.app.NotificationManager.IMPORTANCE_LOW);
// Configure the notification channel.
		mChannel.setDescription(context.getString(descriptionId));
		mChannel.enableLights(true);
		mChannel.setLightColor(Color.GREEN);
		mChannel.enableVibration(useVibration);
		mChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
		mNotificationManager.createNotificationChannel(mChannel);
	}
}
