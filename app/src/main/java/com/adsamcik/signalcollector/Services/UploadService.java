package com.adsamcik.signalcollector.Services;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.LoadAndUploadTask;
import com.adsamcik.signalcollector.Setting;

public class UploadService extends JobService {
	LoadAndUploadTask task;

	public boolean upload() {
		Log.d("UploadService", "Upload started");
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
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		Log.d("UploadService", "Job scheduled");
		return !upload();
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if(task != null)
			task.cancel(true);
		return false;
	}
}
