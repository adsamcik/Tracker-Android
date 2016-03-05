package com.adsamcik.signalcollector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ConnectivityChangeReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d("tag", intent.getAction());
		DataStore.updateAutoUploadState(context);
	}
}