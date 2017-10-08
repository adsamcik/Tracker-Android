package com.adsamcik.signalcollector.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.enums.ResolvedActivity;
import com.adsamcik.signalcollector.utility.ActivityInfo;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.Preferences;

public class ActivityWakerService extends Service {
	private NotificationManager notificationManager;
	private final int NOTIFICATION_ID = 568465;
	private Thread thread;

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		startForeground(NOTIFICATION_ID, updateNotification());

		ActivityService.requestAutoTracking(this, getClass());

		thread = new Thread(() -> {
			//Is not supposed to quit while until service is stopped
			//noinspection InfiniteLoopStatement
			while (true) {
				try {
					Thread.sleep(Preferences.get(this).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE * Constants.SECOND_IN_MILLISECONDS));
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				notificationManager.notify(NOTIFICATION_ID, updateNotification());
			}

		});

		thread.start();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		ActivityService.removeAutoTracking(this, getClass());
	}

	private Notification updateNotification() {
		Intent intent = new Intent(this, MainActivity.class);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getString(R.string.channel_track_id))
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setTicker(getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentIntent(PendingIntent.getActivity(this, 0, intent, 0)) // The intent to send when the entry is clicked
				.setColor(ContextCompat.getColor(this, R.color.color_accent));

		builder.setContentTitle(getString(R.string.notification_activity_watcher));
		ActivityInfo activityInfo = ActivityService.getLastActivity();
		builder.setContentText(getString(R.string.notification_activity_watcher_info, activityInfo.getActivityName(), activityInfo.confidence));
		switch (activityInfo.resolvedActivity) {
			case ResolvedActivity.IN_VEHICLE:
				builder.setSmallIcon(R.drawable.ic_directions_car_black_24dp);
				break;
			case ResolvedActivity.ON_FOOT:
				builder.setSmallIcon(R.drawable.ic_directions_walk_black_24dp);
				break;
			case ResolvedActivity.STILL:
				builder.setSmallIcon(R.drawable.ic_accessibility_black_24dp);
				break;
			case ResolvedActivity.UNKNOWN:
				builder.setSmallIcon(R.drawable.ic_help_black_24dp);
				break;
		}

		return builder.build();
	}
}
