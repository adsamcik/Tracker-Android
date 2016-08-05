package com.adsamcik.signalcollector.play;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.interfaces.IContextCallback;
import com.adsamcik.signalcollector.services.PlayIntentService;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.ActivityRecognition;
import com.google.firebase.crash.FirebaseCrash;

import java.lang.ref.WeakReference;

public class ActivityController implements GoogleApiClient.ConnectionCallbacks {
	private GoogleApiClient client;

	private WeakReference<Context> appContext;

	ActivityController(@NonNull Context context) {
		this.appContext = new WeakReference<>(context.getApplicationContext());
	}

	public void setClient(GoogleApiClient client) {
		this.client = client;
	}

	@Override
	public void onConnected(Bundle bundle) {
		Context context = appContext.get();
		Intent i = new Intent(context, PlayIntentService.class);
		PendingIntent activityPendingIntent = PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

		ActivityRecognition.ActivityRecognitionApi.requestActivityUpdates(client, 0, activityPendingIntent);
	}

	@Override
	public void onConnectionSuspended(int i) {

	}
}
