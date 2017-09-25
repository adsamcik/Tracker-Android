package com.adsamcik.signalcollector.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.adsamcik.signalcollector.activities.ActivityRecognitionActivity;
import com.adsamcik.signalcollector.enums.ResolvedActivity;
import com.adsamcik.signalcollector.utility.ActivityInfo;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.android.gms.common.api.GoogleApi;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionClient;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.android.gms.tasks.Task;
import com.google.firebase.crash.FirebaseCrash;

public class ActivityService extends IntentService {
	private static final String TAG = "Signals" + ActivityService.class.getSimpleName();
	private static final int REQUIRED_CONFIDENCE = 75;
	private static final int MIN_DELAY = 5000;
	private static final int REQUEST_CODE_PENDING_INTENT = 4561201;

	private static ActivityInfo lastActivity = new ActivityInfo(DetectedActivity.UNKNOWN, 0);

	private static Task task;
	private static PowerManager powerManager;

	private static int activeRequestCount;
	private static boolean backgroundTracking;

	public ActivityService() {
		super("ActivityService");
	}

	public static boolean requestActivity(@NonNull Context context) {
		if (activeRequestCount == 0)
			if (!initializeActivityClient(context)) {
				FirebaseCrash.report(new Throwable("Failed to start activity recognition service"));
				return false;
			}
		activeRequestCount++;
		return true;
	}

	public static boolean requestAutoTracking(@NonNull Context context) {
		if (!backgroundTracking && Preferences.get(context).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING) > 0) {
			if (requestActivity(context)) {
				backgroundTracking = true;
				return true;
			}
		}

		return false;
	}

	public static void removeActivityRequest(@NonNull Context context) {
		if (activeRequestCount == 0)
			FirebaseCrash.report(new Throwable("Trying to remove more activity requests than existed"));
		else if (--activeRequestCount == 0) {
			ActivityRecognition.getClient(context).removeActivityUpdates(getActivityDetectionPendingIntent(context));
		}
	}

	public static void removeAutoTracking(@NonNull Context context) {
		if (!backgroundTracking) {
			FirebaseCrash.report(new Throwable("Trying to remove auto tracking request that never existed"));
			return;
		}

		removeActivityRequest(context);
		backgroundTracking = false;
	}

	private static boolean initializeActivityClient(@NonNull Context context) {
		if (Assist.isPlayServiceAvailable(context)) {
			ActivityRecognitionClient activityRecognitionClient = ActivityRecognition.getClient(context);
			task = activityRecognitionClient.requestActivityUpdates(MIN_DELAY, getActivityDetectionPendingIntent(context));
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
