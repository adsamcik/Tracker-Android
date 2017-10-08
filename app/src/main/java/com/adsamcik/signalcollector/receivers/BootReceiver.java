package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.services.ActivityService;
import com.adsamcik.signalcollector.utility.Preferences;

import java.util.Objects;

public class BootReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		if (Objects.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED))
			ActivityService.requestAutoTracking(context, getClass());
	}
}
