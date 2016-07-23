package com.adsamcik.signalcollector.play;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.adsamcik.signalcollector.interfaces.IContextCallback;
import com.adsamcik.signalcollector.services.PlayIntentService;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;

public class ActivityController implements GoogleApiClient.ConnectionCallbacks {
	private GoogleApiClient client;
	private IContextCallback callback;

	ActivityController(IContextCallback callback) {
		this.callback = callback;
	}

	public void setClient(GoogleApiClient client) {
		this.client = client;
	}

	@Override
	public void onConnected(Bundle bundle) {
		Context context = callback.getContext();
		Intent i = new Intent(context, PlayIntentService.class);
		PendingIntent activityPendingIntent = PendingIntent
				.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

		ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(client, 0, activityPendingIntent);
		callback = null;
	}

	@Override
	public void onConnectionSuspended(int i) {

	}
}
