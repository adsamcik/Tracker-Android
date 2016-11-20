package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.adsamcik.signalcollector.receivers.ShortcutReceiver;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.Shortcuts;
import com.adsamcik.signalcollector.utility.Shortcuts.ShortcutType;

public class ShortcutActivity extends Activity {
	public static final String ACTION_STRING = "ShortcutAction";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		if(intent.getAction().equals(Shortcuts.ACTION)) {
			int value = intent.getIntExtra(ACTION_STRING, -1);
			if (value >= 0 && value < ShortcutType.values().length) {
				ShortcutType type = ShortcutType.values()[value];
				Intent serviceIntent = new Intent(this, TrackerService.class);

				switch (type) {
					case START_COLLECTION:
						serviceIntent.putExtra("backTrack", true);
						startService(serviceIntent);
						break;
					case STOP_COLLECTION:
						if (TrackerService.isRunning())
							stopService(serviceIntent);
						break;
				}
			}
		}
		finish();
	}
}
