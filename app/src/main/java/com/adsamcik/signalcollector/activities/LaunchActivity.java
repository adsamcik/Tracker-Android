package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.adsamcik.signalcollector.utility.Preferences;

public class LaunchActivity extends Activity {


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		SharedPreferences sp = Preferences.get(this);
		if (sp.getInt(Preferences.LAST_VERSION, 0) < 113) {
			SharedPreferences.Editor editor = sp.edit();
			editor.remove(Preferences.OBSOLETE_AVAILABLE_MAPS_LAST_UPDATE).remove(Preferences.OBSOLETE_GENERAL_STATS_LAST_UPDATE);
			if (sp.getInt(Preferences.LAST_VERSION, 0) < 106) {
				editor.remove(Preferences.STATS_UPLOADED);
			}
			try {
				editor.putInt(Preferences.LAST_VERSION, getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
			} catch (PackageManager.NameNotFoundException e) {
				//srsly
			} finally {
				editor.apply();
			}
		}

		if (sp.getBoolean(Preferences.HAS_BEEN_LAUNCHED, false))
			startActivity(new Intent(this, MainActivity.class));
		else
			startActivity(new Intent(this, IntroActivity.class));
		overridePendingTransition(0, 0);
		finish();
	}
}
