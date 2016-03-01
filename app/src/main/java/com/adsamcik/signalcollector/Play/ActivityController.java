package com.adsamcik.signalcollector.Play;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.Services.PlayIntentService;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;

public class ActivityController implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
	private GoogleApiClient client;
	private final Context context;

	public ActivityController(Context context) {
		this.context = context;
	}

	public void setClient(GoogleApiClient client) {
		this.client = client;
	}

	@Override
	public void onConnected(Bundle bundle) {
		PlayController.apiActivity = true;
		Intent i = new Intent(context, PlayIntentService.class);
		PendingIntent activityPendingIntent = PendingIntent
				.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

		ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(client, 0, activityPendingIntent);
	}

	@Override
	public void onConnectionSuspended(int i) {

	}

	@Override
	public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

	}
}
