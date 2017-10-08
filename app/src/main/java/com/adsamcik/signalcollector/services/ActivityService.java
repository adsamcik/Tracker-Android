package com.adsamcik.signalcollector.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.util.SparseIntArray;

import com.adsamcik.signalcollector.activities.ActivityRecognitionActivity;
import com.adsamcik.signalcollector.enums.ResolvedActivity;
import com.adsamcik.signalcollector.utility.ActivityInfo;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.tasks.Task;
import com.google.firebase.crash.FirebaseCrash;

public class ActivityService extends IntentService {
	private static final String TAG = "Signals" + ActivityService.class.getSimpleName();
	private static final int REQUIRED_CONFIDENCE = 75;
	private static final int REQUEST_CODE_PENDING_INTENT = 4561201;

	private static ActivityInfo lastActivity = new ActivityInfo(DetectedActivity.UNKNOWN, 0);

	private static Task task;
	private static PowerManager powerManager;

	private static boolean backgroundTracking;

	private static SparseIntArray activeRequests = new SparseIntArray();
	private static int minUpdateRate = Integer.MIN_VALUE;

	public ActivityService() {
		super("ActivityService");
	}

	/**
	 * Request activity updates
	 *
	 * @param context    context
	 * @param tClass     class that requests update
	 * @param updateRate update rate in seconds
	 * @return true if success
	 */
	public static boolean requestActivity(@NonNull Context context, @NonNull Class tClass, int updateRate) {
		return requestActivity(context, tClass.hashCode(), updateRate);
	}

	/**
	 * Request activity updates
	 *
	 * @param context context
	 * @param tClass  class that requests update
	 * @return true if success
	 */
	public static boolean requestActivity(@NonNull Context context, @NonNull Class tClass) {
		return requestActivity(context, tClass.hashCode(), Preferences.get(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE));
	}

	private static boolean requestActivity(@NonNull Context context, int hash, int updateRate) {
		setMinUpdateRate(context, updateRate);
		activeRequests.append(hash, updateRate);
		return true;
	}

	public static boolean requestAutoTracking(@NonNull Context context, @NonNull Class tClass) {
		if (!backgroundTracking && Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING) > 0) {
			if (requestActivity(context, tClass.hashCode(), Preferences.get(context).getInt(Preferences.PREF_ACTIVITY_UPDATE_RATE, Preferences.DEFAULT_ACTIVITY_UPDATE_RATE))) {
				backgroundTracking = true;
				return true;
			}
		}
		return false;
	}

	public static void removeActivityRequest(@NonNull Context context, @NonNull Class tClass) {
		int index = activeRequests.indexOfKey(tClass.hashCode());
		if (index >= 0) {
			if (minUpdateRate == activeRequests.valueAt(index)) {
				setMinUpdateRate(context, getMinUpdateRate());
			}

			activeRequests.removeAt(index);
		} else
			FirebaseCrash.report(new Throwable("Trying to remove class that is not subscribed (" + tClass.getName() + ")"));

		if (activeRequests.size() == 0) {
			ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context));
			activeRequests = new SparseIntArray();
		}
	}

	public static void removeAutoTracking(@NonNull Context context, @NonNull Class tClass) {
		if (!backgroundTracking) {
			FirebaseCrash.report(new Throwable("Trying to remove auto tracking request that never existed"));
			return;
		}

		removeActivityRequest(context, tClass);
		backgroundTracking = false;
	}

	private static void setMinUpdateRate(@NonNull Context context, int minUpdateRate) {
		if (minUpdateRate < ActivityService.minUpdateRate) {
			ActivityService.minUpdateRate = minUpdateRate;
			initializeActivityClient(context, minUpdateRate);
		}
	}

	private static int getMinUpdateRate() {
		if (activeRequests.size() == 0)
			return Integer.MIN_VALUE;

		int min = activeRequests.get(0);
		for (int i = 1; i < activeRequests.size(); i++) {
			if (activeRequests.valueAt(i) < min)
				min = activeRequests.valueAt(i);
		}
		return min;
	}

	private static boolean initializeActivityClient(@NonNull Context context, int delay) {
		if (Assist.isPlayServiceAvailable(context)) {
			ActivityRecognitionClient activityRecognitionClient = ActivityRecognition.getClient(context);
			task = activityRecognitionClient.requestActivityUpdates(delay, getActivityDetectionPendingIntent(context));
			return true;
		} else {
			FirebaseCrash.report(new Throwable("Unavailable play services"));
			return false;
		}
	}

	public static ActivityInfo getLastActivity() {
		return lastActivity;
	}

	/**
	 * Gets a PendingIntent to be sent for each activity detection.
	 */
	private static PendingIntent getActivityDetectionPendingIntent(@NonNull Context context) {
		Intent intent = new Intent(context.getApplicationContext(), ActivityService.class);
		// We use FLAG_UPDATE_CURRENT so that we get the same pending intent back when calling
		// requestActivityUpdates() and removeActivityUpdates().
		return PendingIntent.getService(context, REQUEST_CODE_PENDING_INTENT, intent, PendingIntent.FLAG_UPDATE_CURRENT);
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
		DetectedActivity detectedActivity = result.getMostProbableActivity();

		lastActivity = new ActivityInfo(detectedActivity.getType(), detectedActivity.getConfidence());
		if (backgroundTracking && lastActivity.confidence >= REQUIRED_CONFIDENCE) {
			if (powerManager == null)
				powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
			if (TrackerService.isRunning()) {
				if (TrackerService.isBackgroundActivated() && !canContinueBackgroundTracking(this, lastActivity.resolvedActivity)) {
					stopService(new Intent(this, TrackerService.class));
					ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.getActivityName(), "stopped tracking");
				} else {
					ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.getActivityName(), null);
				}
			} else if (canBackgroundTrack(this, lastActivity.resolvedActivity) && !TrackerService.isAutoLocked() && !powerManager.isPowerSaveMode() && Assist.canTrack(this)) {
				Intent trackerService = new Intent(this, TrackerService.class);
				trackerService.putExtra("backTrack", true);
				startService(trackerService);
				ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.getActivityName(), "started tracking");
			} else {
				ActivityRecognitionActivity.addLineIfDebug(this, lastActivity.getActivityName(), null);
			}
		}

		/*Log.i(TAG, "_____activities detected");
		for (DetectedActivity da: result.getProbableActivities()) {
			Log.i(TAG, ActivityInfo.getActivityName(da.getType()) + " " + da.getConfidence() + "%"
			);
		}*/
	}

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param evalActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private static boolean canBackgroundTrack(@NonNull Context context, @ResolvedActivity int evalActivity) {
		if (evalActivity == 3 || evalActivity == 0 || TrackerService.isRunning() || Preferences.get(context).getBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false))
			return false;
		int val = Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING);
		return val != 0 && (val == evalActivity || val > evalActivity);
	}

	/**
	 * Checks if background tracking should stop
	 *
	 * @param evalActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private static boolean canContinueBackgroundTracking(@NonNull Context context, @ResolvedActivity int evalActivity) {
		if (evalActivity == 0)
			return false;
		int val = Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING);
		return val == 2 || (val == 1 && (evalActivity == 1 || evalActivity == 3));
	}
}
