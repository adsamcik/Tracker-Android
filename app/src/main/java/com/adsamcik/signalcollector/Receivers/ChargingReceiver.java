package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.Preferences;

public class ChargingReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent.getAction().equals(Intent.ACTION_POWER_CONNECTED))
			Preferences.get(context).edit().putBoolean(Preferences.STOP_TILL_RECHARGE, false).apply();
	}
}
