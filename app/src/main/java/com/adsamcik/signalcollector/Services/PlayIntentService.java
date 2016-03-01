package com.adsamcik.signalcollector.Services;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

public class PlayIntentService extends IntentService {
	private static final String TAG = PlayIntentService.class.getSimpleName();

	public PlayIntentService() {
		super("PlayIntentService");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		DataStore.setContext(getApplicationContext());
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if(ActivityRecognitionResult.hasResult(intent)) {
			//Extract the result from the Response
			ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
			DetectedActivity detectedActivity = result.getMostProbableActivity();

			//Get the Confidence and Name of Activity
			int confidence = detectedActivity.getConfidence();
			//String mostProbableName = Extensions.getActivityName(detectedActivity.getType());

			if(TrackerService.isActive) {
				Intent i = new Intent("SCActivity");
				i.putExtra("confidence", confidence);
				i.putExtra("activity", detectedActivity.getType());
				sendBroadcast(i);
			} else if(confidence >= 85 && Extensions.canBackgroundTrack(this, Extensions.EvaluateActivity(detectedActivity.getType()))) {
				Intent trackerService = new Intent(this, TrackerService.class);
				trackerService.putExtra("approxSize", DataStore.sizeOfData());
				trackerService.putExtra("backTrack", true);
				startService(trackerService);
				TrackerService.service = trackerService;
				//Log.d(TAG, "Started service");
				//Log.d(TAG, "Most Probable Name : " + mostProbableName + " type " + detectedActivity.getType());
				//Log.d(TAG, "Confidence : " + confidence);
			}

			//Log.d(TAG, "Most Probable Name : " + mostProbableName + " type " + detectedActivity.getType());
			//Log.d(TAG, "Confidence : " + confidence);

			//Fire the intent with activity name & confidence
	        /*Intent i = new Intent("SCActivity");
            i.putExtra("activityName", mostProbableName);
            i.putExtra("confidence", confidence);
            i.putExtra("activity", detectedActivity.getType());

            //Send Broadcast to be listen in MainActivity
            */


		} else {
			Log.d(TAG, "Intent had no data returned");
		}
	}
}
