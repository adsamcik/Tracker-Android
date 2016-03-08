package com.adsamcik.signalcollector.Receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;

public class ConnectivityChangeReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d("tag", intent.getAction());
		DataStore.updateAutoUploadState(context);
	}
}