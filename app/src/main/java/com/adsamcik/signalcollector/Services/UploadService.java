package com.adsamcik.signalcollector.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Network;
import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.security.InvalidParameterException;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadService extends JobService {
	private static final String TAG = "SignalsUploadService";
	private static final String KEY_IS_AUTOUPLOAD = "isAutoupload";
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
		return queued > 0;
	}

	public static UploadScheduleSource getUploadScheduled(@NonNull Context c) {
		return UploadScheduleSource.values()[Preferences.get(c).getInt(Preferences.SCHEDULED_UPLOAD, 0)];
	}

	/**
	 * Requests upload
	 * Call this when you want to auto-upload
	 *
	 * @param c      Non-null context
	 * @param source Source that started the upload
	 */
	public static void requestUpload(@NonNull Context c, UploadScheduleSource source) {
		if (source.equals(UploadScheduleSource.NONE))
			throw new InvalidParameterException("Upload source can't be NONE.");

		SharedPreferences sp = Preferences.get(c);
		int autoUpload = sp.getInt(Preferences.AUTO_UPLOAD, 1);
		if (autoUpload != 0 || source.equals(UploadScheduleSource.USER)) {
			JobScheduler scheduler = ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE));
			JobInfo.Builder jb = new JobInfo.Builder(Preferences.UPLOAD_JOB, new ComponentName(c, UploadService.class));
			jb.setPersisted(true);
			if (source.equals(UploadScheduleSource.USER)) {
				ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
				//todo implement roaming upload
				if (activeNetwork == null || activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
					jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
				} else {
					if (Build.VERSION.SDK_INT >= 24)
						jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
					else
						jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
				}
			} else {
				if (autoUpload == 2) {
					if (Build.VERSION.SDK_INT >= 24)
						jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
					else
						jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
				} else
					jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
			}
			PersistableBundle pb = new PersistableBundle(1);
			pb.putInt(KEY_IS_AUTOUPLOAD, source.ordinal());
			jb.setExtras(pb);
			scheduler.schedule(jb.build());
			updateUploadScheduleSource(c, source);
		}
	}

	/**
	 * Uploads data to server.
	 *
	 * @param data json array of data
	 * @param name name of the file with the data (Function will clear the file afterwards)
	 */
	private boolean upload(final String data, final String name) {
		if (data.isEmpty()) {
			return false;
		}

		final String serialized = "{\"imei\":" + Assist.getImei() +
				",\"device\":\"" + Build.MODEL +
				"\",\"manufacturer\":\"" + Build.MANUFACTURER +
				"\",\"api\":" + Build.VERSION.SDK_INT +
				",\"version\":" + BuildConfig.VERSION_CODE + "," +
				"\"data\":" + data + "}";

		String imei = Assist.getImei();
		RequestBody formBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("imei", imei)
				.addFormDataPart("hash", Network.generateVerificationString(imei, serialized))
				.addFormDataPart("data", serialized)
				.build();
		Request request = Network.request(Network.URL_DATA_UPLOAD, formBody);
		try {
			Response response = this.client.newCall(request).execute();
			int code = response.code();
			boolean isSuccessful = response.isSuccessful();
			response.close();
			if (isSuccessful) {
				deleteFile(name);
				return true;
			}
			FirebaseCrash.report(new Throwable("Upload failed " + code));
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * @param source source that started the upload
	 * @return true if started
	 */
	private boolean uploadAll(final UploadScheduleSource source) {
		if (source == UploadScheduleSource.NONE)
			throw new InvalidParameterException("Upload source can't be NONE.");

		DataStore.onUpload(0);
		final Context c = getApplicationContext();
		DataStore.setContext(c);
		if (!Assist.isInitialized())
			Assist.initialize(c);
		if (thread == null || !thread.isAlive()) {
			thread = new Thread(() -> {
				Preferences.get(c).edit().putInt(Preferences.SCHEDULED_UPLOAD, UploadScheduleSource.NONE.ordinal()).apply();
				String[] files = DataStore.getDataFileNames(source.equals(UploadScheduleSource.USER));
				if (files == null) {
					FirebaseCrash.report(new Throwable("No files found. This should not happen."));
					DataStore.onUpload(-1);
					return;
				}

				queued = files.length;
				originalQueueLength = files.length;
				for (String fileName : files) {
					queued--;
					if (!Thread.currentThread().isInterrupted()) {
						String data = DataStore.loadJsonArrayAppend(fileName);
						if (data == null) {
							FirebaseCrash.report(new Throwable("File has no data or does not exist. This is really weird."));
							continue;
						}

						if (Assist.canUpload(c, source)) {
							if (!upload(data, fileName)) {
								requestUpload(c, source);
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
				DataStore.recountDataSize();
			});

			thread.start();
			return true;
		}
		FirebaseCrash.report(new Throwable("Upload is initialized multiple times."));
		return false;
	}

	private static void updateUploadScheduleSource(@NonNull Context context, UploadScheduleSource uss) {
		Preferences.get(context).edit().putInt(Preferences.SCHEDULED_UPLOAD, uss.ordinal()).apply();
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		return uploadAll(UploadScheduleSource.values()[jobParameters.getExtras().getInt(KEY_IS_AUTOUPLOAD)]);
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (thread != null && thread.isAlive())
			thread.interrupt();
		DataStore.cleanup();
		DataStore.recountDataSize();
		queued = 0;
		return true;
	}

	public enum UploadScheduleSource {
		NONE,
		BACKGROUND,
		USER
	}
}
