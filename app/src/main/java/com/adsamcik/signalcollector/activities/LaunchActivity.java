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
import android.util.Log;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.services.ActivityWakerService;
import com.adsamcik.signalcollector.services.UploadService;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.NotificationTools;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.Shortcuts;
import com.crashlytics.android.Crashlytics;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.perf.FirebasePerformance;

import java.util.List;

import io.fabric.sdk.android.Fabric;

public class LaunchActivity extends Activity {


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		int theme = Preferences.getTheme(this);
		getApplicationContext().setTheme(theme);
		setTheme(theme);
		super.onCreate(savedInstanceState);
		JobScheduler scheduler = ((JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE));
		assert scheduler != null;
		SharedPreferences sp = Preferences.get(this);
		if (sp.getInt(Preferences.LAST_VERSION, 0) <= 138) {
			SharedPreferences.Editor editor = sp.edit();
			FirebaseAssist.updateValue(this, FirebaseAssist.autoTrackingString, getResources().getStringArray(R.array.background_tracking_options)[Preferences.get(this).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)]);
			FirebaseAssist.updateValue(this, FirebaseAssist.autoUploadString, getResources().getStringArray(R.array.automatic_upload_options)[Preferences.get(this).getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD)]);
			FirebaseAssist.updateValue(this, FirebaseAssist.uploadNotificationString, Boolean.toString(Preferences.get(this).getBoolean(Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED, true)));

			editor.remove(Preferences.PREF_SCHEDULED_UPLOAD);

			try {
				editor.putInt(Preferences.LAST_VERSION, getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
			} catch (PackageManager.NameNotFoundException e) {
				Crashlytics.logException(e);
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
				if (found > 1) {
					scheduler.cancelAll();
					UploadService.requestUpload(this, uss);
				} else if (found == 0) {
					UploadService.requestUpload(this, uss);
				}
			}
		}

		if (sp.getBoolean(Preferences.PREF_HAS_BEEN_LAUNCHED, false) || Assist.isEmulator())
			startActivity(new Intent(this, MainActivity.class));
		else
			startActivity(new Intent(this, IntroActivity.class));

		if (Build.VERSION.SDK_INT >= 25)
			Shortcuts.initializeShortcuts(this);

		if (Build.VERSION.SDK_INT >= 26)
			NotificationTools.prepareChannels(this);

		if (BuildConfig.DEBUG) {
			FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false);
			FirebasePerformance.getInstance().setPerformanceCollectionEnabled(false);
			String token = FirebaseInstanceId.getInstance().getToken();
			Log.d("Signals", token == null ? "null token" : token);
		}

		ActivityWakerService.poke(this);

		overridePendingTransition(0, 0);
		finish();
	}
}
