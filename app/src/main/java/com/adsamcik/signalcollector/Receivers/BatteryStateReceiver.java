package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.Setting;

public class BatteryStateReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		switch(intent.getAction()) {
			case Intent.ACTION_BATTERY_LOW:
				Setting.stopTillRecharge(context);
				if(TrackerService.service != null)
					context.stopService(TrackerService.service);
				break;
		}
	}
}
