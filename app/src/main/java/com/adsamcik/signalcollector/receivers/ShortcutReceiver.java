package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.utility.Shortcuts.ShortcutType;

public class ShortcutReceiver extends BroadcastReceiver {
	public static final String ACTION_STRING = "ShortcutAction";

	@Override
	public void onReceive(Context context, Intent intent) {
		int value = intent.getIntExtra(ACTION_STRING, -1);
		if(value >= 0 && value < ShortcutType.values().length) {
			ShortcutType type = ShortcutType.values()[value];
			Intent serviceIntent = new Intent(context, TrackerService.class);

			switch (type) {
				case START_COLLECTION:
					serviceIntent.putExtra("backTrack", true);
					context.startService(serviceIntent);
					break;
				case STOP_COLLECTION:
					if(TrackerService.isRunning())
						context.stopService(serviceIntent);
					break;
			}
		}
	}
}
