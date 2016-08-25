package com.adsamcik.signalcollector.receivers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.Setting;

public class NotificationReceiver extends BroadcastReceiver {
	private final String TAG = "SignalsNotifiReceiver";
	public static final String ACTION_STRING = "action";

	@Override
	public void onReceive(Context context, Intent intent) {
		int value = intent.getIntExtra(ACTION_STRING, -1);
		switch (value) {
			case 0:
				Setting.stopTillRecharge(context);
				break;
			case 1:
				context.stopService(TrackerService.service);
				break;
			default:
				Log.w(TAG, "Unknown value " + value);
		}
	}
}
