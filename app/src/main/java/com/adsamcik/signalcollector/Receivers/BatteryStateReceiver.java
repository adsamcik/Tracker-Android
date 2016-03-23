package com.adsamcik.signalcollector.Receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.Services.TrackerService;
import com.adsamcik.signalcollector.Setting;

public class BatteryStateReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		switch(intent.getAction()) {
			case Intent.ACTION_BATTERY_LOW:
				Setting.isStopped = true;
				if(TrackerService.isActive)
					context.stopService(TrackerService.service);
				break;
		}
	}
}
