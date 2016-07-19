package com.adsamcik.signalcollector.services;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

import com.adsamcik.signalcollector.DataStore;
import com.adsamcik.signalcollector.Extensions;
import com.adsamcik.signalcollector.Setting;
import com.google.firebase.crash.FirebaseCrash;

import java.nio.charset.Charset;

public class UploadService extends JobService {
	Thread thread;

	/**
	 *
	 * @param background background upload
	 * @return true if started
	 */
	public boolean upload(final boolean background) {
		if (thread == null || !thread.isAlive()) {
			thread = new Thread(() -> {
				String[] files = DataStore.getDataFileNames(!background);

				if (files == null || files.length == 0) {
					Log.e(DataStore.TAG, "No file names were entered");
					FirebaseCrash.report(new Throwable("No file names were entered"));
					return;
				} else if (DataStore.getContext() == null) {
					Log.e(DataStore.TAG, "DataStore context is null");
					FirebaseCrash.report(new Throwable("DataStore context is null"));
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
						Log.d("TAG", "start " + canStart(background));
						long size = builder.toString().getBytes(Charset.defaultCharset()).length;
						if (canStart(background))
							DataStore.upload(builder.toString(), fileName, size, background);
						else
							break;
					} else
						break;
				}

				if (TrackerService.approxSize < 0)
					TrackerService.approxSize = 0;

				DataStore.cleanup();
				DataStore.recountDataSize();

				Setting.getPreferences().edit().putBoolean(Setting.SCHEDULED_UPLOAD, false).apply();
				DataStore.onUpload();
			});
			thread.start();
			return true;
		}
		return false;
	}

	boolean canStart(boolean background) {
		Context c = getApplicationContext();
		return Extensions.canUpload(c, background);
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		Log.d("TAG", "ServiceStarted");
		return upload(jobParameters.getExtras().getInt(DataStore.KEY_IS_AUTOUPLOAD) == 1);
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (thread != null && thread.isAlive())
			thread.interrupt();
		DataStore.cleanup();
		//FirebaseCrash.report(new Throwable("Job canceled"));
		return false;
	}
}
