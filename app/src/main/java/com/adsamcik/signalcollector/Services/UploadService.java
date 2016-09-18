package com.adsamcik.signalcollector.services;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.adsamcik.signalcollector.Assist;
import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.classes.DataStore;
import com.adsamcik.signalcollector.Setting;
import com.adsamcik.signalcollector.classes.Network;
import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.nio.charset.Charset;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadService extends JobService {
	private static final String TAG = "SignalsUploadService";
	private static Thread thread;
	private final OkHttpClient client = new OkHttpClient();
	private static int queued = 0;
	private static int originalQueueLength;

	public static int getUploadPercentage() {
		return thread == null || !thread.isAlive() ? 0 : calculateUploadPercentage();
	}

	private static int calculateUploadPercentage() {
		return (int) ((1 - (queued / (double) originalQueueLength)) * 100);
	}

	public static boolean isUploading() {
		return  queued > 0;
	}

	/**
	 * Uploads data to server.
	 *
	 * @param data json array of Data
	 * @param name name of file where the data is saved (Function will clear the file afterwards)
	 */
	private boolean upload(final String data, final String name) {
		if (data.isEmpty()) {
			FirebaseCrash.report(new Exception("data are empty"));
			return false;
		}

		final String serialized = "{\"imei\":" + Assist.getImei() +
				",\"device\":\"" + Build.MODEL +
				"\",\"manufacturer\":\"" + Build.MANUFACTURER +
				"\",\"api\":" + Build.VERSION.SDK_INT +
				",\"version\":" + BuildConfig.VERSION_CODE + "," +
				"\"data\":" + data + "}";

		RequestBody formBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("imei", Assist.getImei())
				.addFormDataPart("data", serialized)
				.build();
		Request request = Network.request(Network.URL_DATA_UPLOAD, formBody);
		try {
			Response response = this.client.newCall(request).execute();
			boolean isSuccessful = response.isSuccessful();
			response.close();
			if (isSuccessful) {
				deleteFile(name);
				return true;
			}
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * @param background background upload
	 * @return true if started
	 */
	private boolean uploadAll(final boolean background) {
		DataStore.onUpload(0);
		final Context c = getApplicationContext();
		DataStore.setContext(c);
		if (!Assist.isInitialized())
			Assist.initialize(c);
		if (thread == null || !thread.isAlive()) {
			thread = new Thread(() -> {
				Setting.getPreferences(c).edit().putBoolean(Setting.SCHEDULED_UPLOAD, false).apply();
				String[] files = DataStore.getDataFileNames(!background);
				if (files == null || files.length == 0) {
					Log.e(DataStore.TAG, "No file names were entered");
					FirebaseCrash.report(new Throwable("No file names were entered"));
					DataStore.onUpload(-1);
					return;
				}

				queued = files.length;
				originalQueueLength = files.length;
				long originalSize = DataStore.recountDataSize();
				for (String fileName : files) {
					queued--;
					if (!Thread.currentThread().isInterrupted()) {
						if (fileName == null || fileName.trim().length() == 0) {
							Log.e(DataStore.TAG, "Null or empty file name was in load and upload task. This should not happen.");
							FirebaseCrash.report(new Exception("Null or empty file name was in load and upload task. This should not happen."));
							continue;
						}

						StringBuilder builder = DataStore.loadStringAsBuilder(fileName);

						if (builder == null || builder.length() == 0) {
							String issue = builder == null ? "does not exist" : "is empty";
							Log.e(DataStore.TAG, "File " + fileName + " " + issue + ". This should not happen.");
							FirebaseCrash.report(new Exception("File " + fileName + " " + issue + ". This should not happen."));
							continue;
						} else {
							builder.setCharAt(0, '[');
							builder.append(']');
						}
						if (Assist.canUpload(c, background)) {
							if (!upload(builder.toString(), fileName)) {
								DataStore.requestUpload(c, true);
								DataStore.onUpload(-1);
								break;
							}
							DataStore.onUpload(calculateUploadPercentage());
						} else {
							DataStore.onUpload(-1);
							break;
						}
					} else {
						DataStore.onUpload(-1);
						break;
					}
				}

				DataStore.cleanup();
				updateUploadedStat(originalSize - DataStore.recountDataSize());
			});

			thread.start();
			return true;
		}
		return false;
	}

	void updateUploadedStat(long uploaded) {
		FirebaseCrash.report(new Throwable("uploaded " + uploaded));
		SharedPreferences sp = Setting.getPreferences(getApplicationContext());
		sp.edit().putLong(Setting.STATS_UPLOADED, sp.getLong(Setting.STATS_UPLOADED, 0) + uploaded).apply();
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		return uploadAll(jobParameters.getExtras().getInt(DataStore.KEY_IS_AUTOUPLOAD) == 1);
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (thread != null && thread.isAlive())
			thread.interrupt();
		updateUploadedStat(DataStore.sizeOfData() - DataStore.recountDataSize());
		DataStore.cleanup();
		queued = 0;
		return false;
	}
}
