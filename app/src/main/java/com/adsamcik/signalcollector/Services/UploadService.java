package com.adsamcik.signalcollector.services;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.MainActivity;
import com.adsamcik.signalcollector.Setting;
import com.google.firebase.crash.FirebaseCrash;

import java.nio.charset.Charset;

public class UploadService extends JobService {
	Thread thread;

	public boolean upload(final boolean autoUpload) {
		if (canStart(autoUpload)) {
			thread = new Thread(new Runnable() {
				public void run() {
					String[] files = DataStore.getDataFileNames(!autoUpload);
					if (files.length == 0) {
						Log.e(DataStore.TAG, "No file names were entered");
						return;
					} else if (DataStore.getContext() == null) {
						Log.e(DataStore.TAG, "DataStore context is null");
						return;
					}

					TrackerService.approxSize = DataStore.sizeOfData();

					for (String fileName : files) {
						if (!Thread.currentThread().isInterrupted()) {
							if (fileName == null || fileName.trim().length() == 0) {
								Log.e(DataStore.TAG, "Null or empty file name was in load and upload task. This should not happen.");
								FirebaseCrash.report(new Exception("Null or empty file name was in load and upload task. This should not happen."));
								continue;
							}

							StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

							if (builder == null || builder.length() == 0) {
								Log.e(DataStore.TAG, "File" + fileName + " did not exist or was empty. This should not happen.");
								FirebaseCrash.report(new Exception("File" + fileName + " did not exist or was empty. This should not happen."));
								continue;
							} else {
								builder.setCharAt(0, '[');
								builder.append(']');
							}

							long size = builder.toString().getBytes(Charset.defaultCharset()).length;
							if (canStart(autoUpload))
								DataStore.upload(builder.toString(), fileName, size);
							else
								break;
						} else
							break;
					}

					if (TrackerService.approxSize < 0)
						TrackerService.approxSize = 0;

					Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
					if (TrackerService.approxSize == 0)
						intent.putExtra("cloudStatus", 0);
					LocalBroadcastManager.getInstance(DataStore.getContext()).sendBroadcast(intent);

					DataStore.cleanup();
					DataStore.recountDataSize();

					Setting.getPreferences().edit().putBoolean(Setting.SCHEDULED_UPLOAD, false).apply();
				}
			});
			thread.start();
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
		if (thread.isAlive())
			thread.interrupt();
		FirebaseCrash.log("Job canceled");
		return false;
	}
}
