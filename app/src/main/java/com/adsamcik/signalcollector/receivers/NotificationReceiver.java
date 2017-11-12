package com.adsamcik.signalcollector.receivers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.analytics.FirebaseAnalytics;

public class NotificationReceiver extends BroadcastReceiver {
	private final String TAG = "SignalsNotifiReceiver";
	public static final String ACTION_STRING = "action";

	@Override
	public void onReceive(Context context, Intent intent) {
		int value = intent.getIntExtra(ACTION_STRING, -1);
		Bundle params = new Bundle();
		params.putString(FirebaseAssist.PARAM_SOURCE, "notification");
		switch (value) {
			case 0:
				Preferences.stopTillRecharge(context);
				FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.STOP_TILL_RECHARGE_EVENT, params);
				break;
			case 1:
				FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.STOP_EVENT, params);
				context.stopService(new Intent(context, TrackerService.class));
				break;
			default:
				Log.w(TAG, "Unknown value " + value);
		}
	}
}
