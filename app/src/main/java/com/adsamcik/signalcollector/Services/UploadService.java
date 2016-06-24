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

	public boolean upload() {
		FirebaseCrash.log("Upload started");
		Context c = getApplicationContext();
		int autoUpload = Setting.getPreferences(c).getInt(Setting.AUTO_UPLOAD, 1);
		if (autoUpload >= 1) {
			ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
			NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

			if (activeNetwork != null &&
					activeNetwork.isConnectedOrConnecting() &&
					(activeNetwork.getType() == ConnectivityManager.TYPE_WIFI ||
							(autoUpload == 2 &&
									activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE &&
									!activeNetwork.isRoaming()))) {
				task = new LoadAndUploadTask();
				task.execute(DataStore.getDataFileNames(false));
				FirebaseCrash.log("Upload successful");
				return true;
			}
		}
		FirebaseCrash.log("Upload failed");
		return false;
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		FirebaseCrash.log("Job scheduled");
		return !upload();
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if(task != null)
			task.cancel(true);
		FirebaseCrash.log("Job canceled");
		return false;
	}
}
