package com.adsamcik.signalcollector.activities;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.services.UploadService;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Network;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.Shortcuts;
import com.google.firebase.crash.FirebaseCrash;

import java.util.Iterator;
import java.util.List;

public class LaunchActivity extends Activity {


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DataStore.setContext(this);
		SharedPreferences sp = Preferences.get(this);
		JobScheduler scheduler = ((JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE));
		if (sp.getInt(Preferences.LAST_VERSION, 0) <= 138) {
			SharedPreferences.Editor editor = sp.edit();
			FirebaseAssist.updateValue(this, FirebaseAssist.autoTrackingString, getResources().getStringArray(R.array.background_tracking_options)[Preferences.get(this).getInt(Preferences.BACKGROUND_TRACKING, 0)]);
			FirebaseAssist.updateValue(this, FirebaseAssist.autoUploadString, getResources().getStringArray(R.array.automatic_upload_options)[Preferences.get(this).getInt(Preferences.AUTO_UPLOAD, 0)]);
			FirebaseAssist.updateValue(this, FirebaseAssist.uploadNotificationString, Boolean.toString(Preferences.get(this).getBoolean(Preferences.UPLOAD_NOTIFICATIONS_ENABLED, true)));

			editor.remove(Preferences.SCHEDULED_UPLOAD);

			try {
				editor.putInt(Preferences.LAST_VERSION, getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
			} catch (PackageManager.NameNotFoundException e) {
				FirebaseCrash.report(e);
			}
			editor.apply();

			scheduler.cancelAll();
		} else {
			UploadService.UploadScheduleSource uss = UploadService.getUploadScheduled(this);
			if (!uss.equals(UploadService.UploadScheduleSource.NONE)) {
				List<JobInfo> jobs = scheduler.getAllPendingJobs();

				int found = 0;
				for (JobInfo job : jobs) {
					if (job.getService().getClassName().equals("UploadService")) {
						found++;
					}
				}
				if (found != 1) {
					scheduler.cancelAll();
					UploadService.requestUpload(this, uss);
				}
			}
		}

		if (sp.getBoolean(Preferences.HAS_BEEN_LAUNCHED, false))
			startActivity(new Intent(this, MainActivity.class));
		else
			startActivity(new Intent(this, IntroActivity.class));

		if (Build.VERSION.SDK_INT >= 25)
			Shortcuts.initializeShortcuts(this);

		overridePendingTransition(0, 0);
		finish();
	}
}
