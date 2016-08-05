package com.adsamcik.signalcollector.receivers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.Setting;
import com.google.firebase.crash.FirebaseCrash;

public class NotificationReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if(context == null || TrackerService.service == null)
			FirebaseCrash.report(new Throwable("context is " + (context == null ? "NULL" : "OK") + " service is " + (TrackerService.service == null ? "NULL" : "OK")));
		else {
			Setting.stopTillRecharge(context);
			context.stopService(TrackerService.service);
		}
	}
}
