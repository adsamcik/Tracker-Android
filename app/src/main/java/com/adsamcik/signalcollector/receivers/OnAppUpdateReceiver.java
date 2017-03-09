package com.adsamcik.signalcollector.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.crash.FirebaseCrash;

public class OnAppUpdateReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		SharedPreferences sp = Preferences.get(context);
		SharedPreferences.Editor editor = sp.edit();
		DataStore.setContext(context);
		Assist.initialize(context);
		DataStore.cleanup();

		if(sp.getInt(Preferences.LAST_VERSION, 0) < 142)
			DataStore.clearAllData();


		try {
			editor.putInt(Preferences.LAST_VERSION, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
		} catch (PackageManager.NameNotFoundException e) {
			FirebaseCrash.report(e);
		}
		editor.apply();
	}
}
