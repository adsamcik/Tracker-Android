package com.adsamcik.signalcollector.services;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.async.LoadAndUploadTask;
import com.adsamcik.signalcollector.Setting;
import com.google.firebase.crash.FirebaseCrash;

public class UploadService extends JobService {
	LoadAndUploadTask task;

	public boolean upload(boolean autoUpload) {
		if (canStart(autoUpload)) {
			task = new LoadAndUploadTask();
			task.execute(DataStore.getDataFileNames(!autoUpload));
			return true;
		}
		return false;
	}

	boolean canStart(boolean autoUpload) {
		Context c = getApplicationContext();
		ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

		if (!autoUpload) {
			return !activeNetwork.isRoaming();
		} else {
			int aVal = Setting.getPreferences(c).getInt(Setting.AUTO_UPLOAD, 1);
			return activeNetwork != null && activeNetwork.isConnectedOrConnecting() &&
					(activeNetwork.getType() == ConnectivityManager.TYPE_WIFI ||
							(aVal == 2 && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE && !activeNetwork.isRoaming()));
		}
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		FirebaseCrash.log("Job scheduled");
		return !upload(jobParameters.getExtras().getInt(DataStore.KEY_IS_AUTOUPLOAD) == 1);
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (task != null)
			task.cancel(true);
		FirebaseCrash.log("Job canceled");
		return false;
	}
}
