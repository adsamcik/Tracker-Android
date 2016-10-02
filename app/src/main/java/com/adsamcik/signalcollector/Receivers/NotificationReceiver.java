package com.adsamcik.signalcollector.receivers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.adsamcik.signalcollector.Preferences;
import com.adsamcik.signalcollector.services.TrackerService;

public class NotificationReceiver extends BroadcastReceiver {
	private final String TAG = "SignalsNotifiReceiver";
	public static final String ACTION_STRING = "action";

	@Override
	public void onReceive(Context context, Intent intent) {
		int value = intent.getIntExtra(ACTION_STRING, -1);
		switch (value) {
			case 0:
				Preferences.stopTillRecharge(context);
				break;
			case 1:
				context.stopService(TrackerService.service);
				break;
			default:
				Log.w(TAG, "Unknown value " + value);
		}
	}
}
