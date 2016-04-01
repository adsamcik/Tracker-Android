package com.adsamcik.signalcollector.Receivers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.Services.TrackerService;
import com.adsamcik.signalcollector.Setting;

public class NotificationReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		Setting.stopTillRecharge(context);
		context.stopService(TrackerService.service);
	}
}
