package com.adsamcik.signalcollector.services;

import android.app.IntentService;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.play.ActivityController;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

public class PlayIntentService extends IntentService {
	private static final String TAG = PlayIntentService.class.getSimpleName();
	PowerManager powerManager;

	public PlayIntentService() {
		super("PlayIntentService");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		DataStore.setContext(getApplicationContext());
		powerManager = (PowerManager) getSystemService(POWER_SERVICE);
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if(ActivityRecognitionResult.hasResult(intent)) {
			//Extract the result from the Response
			ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
			DetectedActivity detectedActivity = result.getMostProbableActivity();

			//Get the Confidence and Name of Activity
			int confidence = detectedActivity.getConfidence();
			//String mostProbableName = Assist.getActivityName(detectedActivity.getType());

			//Log.d(TAG, Assist.getActivityName(detectedActivity.getType()) + " confident " + confidence);

			if(TrackerService.service != null) {
				Intent i = new Intent("SCActivity");
				i.putExtra("confidence", confidence);
				i.putExtra("activity", detectedActivity.getType());
				sendBroadcast(i);
			} else if(confidence >= ActivityController.REQUIRED_PROBABILITY && Assist.canBackgroundTrack(this, Assist.evaluateActivity(detectedActivity.getType())) && !TrackerService.isAutoLocked() && !powerManager.isPowerSaveMode()) {
				Intent trackerService = new Intent(this, TrackerService.class);
				trackerService.putExtra("approxSize", DataStore.sizeOfData());
				trackerService.putExtra("backTrack", true);
				startService(trackerService);
			}
		} else {
			Log.d(TAG, "Intent had no data returned");
		}
	}
}
