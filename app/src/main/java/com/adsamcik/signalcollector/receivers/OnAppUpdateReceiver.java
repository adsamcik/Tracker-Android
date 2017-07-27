package com.adsamcik.signalcollector.receivers;

import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.utility.NotificationTools;
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
			if (sp.getInt(Preferences.LAST_VERSION, 0) < 159) {
				DataStore.clearAllData(context);
				JobScheduler scheduler = ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE));
				assert scheduler != null;
				scheduler.cancelAll();
			}

			try {
				editor.putInt(Preferences.LAST_VERSION, context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode);
			} catch (PackageManager.NameNotFoundException e) {
				FirebaseCrash.report(e);
			}
			editor.apply();
		}

		if(Build.VERSION.SDK_INT >= 26)
			NotificationTools.prepareChannels(context);
	}
}
