package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.crash.FirebaseCrash;

public class OnAppUpdateReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (action != null && action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)) {
			SharedPreferences sp = Preferences.get(context);
			SharedPreferences.Editor editor = sp.edit();
			Assist.initialize(context);

			if(sp.getInt(Preferences.LAST_VERSION, 0) < 207) {
				DataStore.setCollections(context, 0);
			}

			int currentDataFile = sp.getInt(DataStore.PREF_DATA_FILE_INDEX, -1);
			if(currentDataFile >= 0 && DataStore.exists(context, DataStore.DATA_FILE + currentDataFile)) {
				DataStore.getCurrentDataFile(context).close();
				editor.putInt(DataStore.PREF_DATA_FILE_INDEX, ++currentDataFile);
			}

			try {
				editor.putInt(Preferences.LAST_VERSION, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
			} catch (PackageManager.NameNotFoundException e) {
				FirebaseCrash.report(e);
			}
			editor.apply();
		}
	}
}
