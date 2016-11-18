package com.adsamcik.signalcollector.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Failure;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;
import com.google.firebase.crash.FirebaseCrash;

public class ActivityService extends IntentService {
	private static final String TAG = "Signals" + ActivityService.class.getSimpleName();
	private static GoogleApiClient gapiClient;
	private PowerManager powerManager;
	public static int lastActivity;
	public static int lastConfidence;

	public static final int GOOGLE_API_ID = 77285;
	public static final int REQUIRED_CONFIDENCE = 75;


	public static Failure<String> initializeActivityClient(@NonNull Context context) {
		if (Assist.isPlayServiceAvailable(context)) {
			if (gapiClient == null) {
				final Context appContext = context.getApplicationContext();
				if (appContext == null) {
					FirebaseCrash.report(new Throwable("Application context is null"));
					return new Failure<>("Failed to initialize automatic tracking");
				}
				gapiClient = new GoogleApiClient.Builder(appContext)
						.addApi(ActivityRecognition.API)
						.addOnConnectionFailedListener(connectionResult -> FirebaseCrash.report(new Throwable("Failed to initialize activity " + connectionResult.getErrorMessage() + " code " + connectionResult.getErrorCode())))
						.addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
							@Override
							public void onConnected(@Nullable Bundle bundle) {
								ActivityService.requestUpdate(gapiClient, context);
							}

							@Override
							public void onConnectionSuspended(int i) {

							}
						})
						.build();
			}
			gapiClient.connect();
			return new Failure<>();
		}
		return new Failure<>("Play services are not available");
	}

	public static Failure<String> initializeActivityClient(@NonNull FragmentActivity activity) {
		if (Assist.isPlayServiceAvailable(activity)) {
			if (gapiClient == null) {
				final Context appContext = activity.getApplicationContext();
				if (appContext == null) {
					FirebaseCrash.report(new Throwable("Application context is null"));
					return new Failure<>("Failed to initialize automatic tracking");
				}
				gapiClient = new GoogleApiClient.Builder(appContext)
						.addApi(ActivityRecognition.API)
						.addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
							@Override
							public void onConnected(@Nullable Bundle bundle) {
								ActivityService.requestUpdate(gapiClient, activity);
							}

							@Override
							public void onConnectionSuspended(int i) {

							}
						})
						.enableAutoManage(activity, ActivityService.GOOGLE_API_ID, null)
						.build();
			}
			gapiClient.connect();
			return new Failure<>();
		}
		return new Failure<>("Play services are not available");
	}

	public static void requestUpdate(@NonNull GoogleApiClient client, @NonNull final Context context) {
		Intent i = new Intent(context, ActivityService.class);
		PendingIntent activityPendingIntent = PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
		ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(client, 0, activityPendingIntent);
	}

	public ActivityService() {
		super("ActivityService");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		DataStore.setContext(getApplicationContext());
		powerManager = (PowerManager) getSystemService(POWER_SERVICE);
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if (ActivityRecognitionResult.hasResult(intent)) {
			ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
			DetectedActivity detectedActivity = result.getMostProbableActivity();

			lastConfidence = detectedActivity.getConfidence();
			lastActivity = detectedActivity.getType();
			int evalActivity = Assist.evaluateActivity(detectedActivity.getType());

			if (lastConfidence >= REQUIRED_CONFIDENCE) {
				if (TrackerService.isRunning()) {
					if (TrackerService.isBackgroundActivated() && !canContinueBackgroundTracking(evalActivity))
						stopService(new Intent(this, TrackerService.class));
				} else if (canBackgroundTrack(evalActivity) && !TrackerService.isAutoLocked() && !powerManager.isPowerSaveMode()) {
					Intent trackerService = new Intent(this, TrackerService.class);
					trackerService.putExtra("approxSize", DataStore.sizeOfData());
					trackerService.putExtra("backTrack", true);
					startService(trackerService);
				}
			}
		} else {
			Log.d(TAG, "Intent had no data returned");
		}
	}

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param evalActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private boolean canBackgroundTrack(int evalActivity) {
		if (evalActivity == 3 || evalActivity == 0 || TrackerService.isRunning() || Preferences.get(this).getBoolean(Preferences.STOP_TILL_RECHARGE, false))
			return false;
		int val = Preferences.get(this).getInt(Preferences.BACKGROUND_TRACKING, 1);
		return val != 0 && (val == evalActivity || val > evalActivity);
	}

	/**
	 * Checks if background tracking should stop
	 *
	 * @param evalActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private boolean canContinueBackgroundTracking(int evalActivity) {
		if (evalActivity == 0)
			return false;
		int val = Preferences.get(this).getInt(Preferences.BACKGROUND_TRACKING, 1);
		return val == 2 || (val == 1 && (evalActivity == 1 || evalActivity == 3));
	}
}
