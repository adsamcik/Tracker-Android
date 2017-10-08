package com.adsamcik.signalcollector.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.activities.MainActivity;
import com.adsamcik.signalcollector.enums.ResolvedActivity;
import com.adsamcik.signalcollector.utility.ActivityInfo;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.Preferences;

public class ActivityWakerService extends Service {
	private static ActivityWakerService instance;
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

		instance = this;

		notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		startForeground(NOTIFICATION_ID, updateNotification());

		ActivityService.requestAutoTracking(this, getClass());

		thread = new Thread(() -> {
			//Is not supposed to quit while until service is stopped
			//noinspection InfiniteLoopStatement
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(Preferences.get(this).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE * Constants.SECOND_IN_MILLISECONDS));
					notificationManager.notify(NOTIFICATION_ID, updateNotification());
				} catch (InterruptedException e) {
					break;
				}
			}

		});

		thread.start();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		ActivityService.removeAutoTracking(this, getClass());
		instance = null;
		thread.interrupt();
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
				builder.setSmallIcon(R.drawable.ic_directions_car_white_24dp);
				break;
			case ResolvedActivity.ON_FOOT:
				builder.setSmallIcon(R.drawable.ic_directions_walk_white_24dp);
				break;
			case ResolvedActivity.STILL:
				builder.setSmallIcon(R.drawable.ic_accessibility_white_24dp);
				break;
			case ResolvedActivity.UNKNOWN:
				builder.setSmallIcon(R.drawable.ic_help_white_24dp);
				break;
		}

		return builder.build();
	}

	/**
	 * Pokes Activity Waker Service which checks if it should run
	 *
	 * @param activity activity
	 */
	public static synchronized void poke(@NonNull Activity activity) {
		if (Preferences.get(activity).getBoolean(Preferences.PREF_ACTIVITY_WATCHER_ENABLED, Preferences.DEFAULT_ACTIVITY_WATCHER_ENABLED)) {
			if (instance == null)
				Assist.startServiceForeground(activity, new Intent(activity, ActivityWakerService.class));
		} else if(instance != null) {
			instance.stopSelf();
		}
	}
}
