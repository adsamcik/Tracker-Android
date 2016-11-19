package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.Shortcuts;

import java.util.ArrayList;
import java.util.Arrays;

public class LaunchActivity extends Activity {


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DataStore.setContext(this);
		SharedPreferences sp = Preferences.get(this);
		if (sp.getInt(Preferences.LAST_VERSION, 0) < 115) {
			SharedPreferences.Editor editor = sp.edit();
			editor.remove(Preferences.AVAILABLE_MAPS);
			if (sp.getInt(Preferences.LAST_VERSION, 0) < 113) {
				if (DataStore.exists("general_stats_cache_file"))
					DataStore.deleteFile("general_stats_cache_file");
				editor.remove(Preferences.OBSOLETE_AVAILABLE_MAPS_LAST_UPDATE).remove(Preferences.OBSOLETE_GENERAL_STATS_LAST_UPDATE).remove(Preferences.AVAILABLE_MAPS);
				if (sp.getInt(Preferences.LAST_VERSION, 0) < 106) {
					editor.remove(Preferences.STATS_UPLOADED);
				}
				try {
					editor.putInt(Preferences.LAST_VERSION, getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
				} catch (PackageManager.NameNotFoundException e) {
					//srsly
				}
			}
			editor.apply();
		}

		if (sp.getBoolean(Preferences.HAS_BEEN_LAUNCHED, false))
			startActivity(new Intent(this, MainActivity.class));
		else
			startActivity(new Intent(this, IntroActivity.class));

		if (android.os.Build.VERSION.SDK_INT >= 25)
			Shortcuts.initializeShortcuts(this);

		overridePendingTransition(0, 0);
		finish();
	}
}
