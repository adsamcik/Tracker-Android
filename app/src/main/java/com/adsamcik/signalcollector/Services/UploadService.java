package com.adsamcik.signalcollector.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Compress;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.utility.DataStore;
import com.adsamcik.signalcollector.utility.Network;
import com.google.firebase.crash.FirebaseCrash;

import java.io.File;
import java.io.IOException;
import java.security.InvalidParameterException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadService extends JobService {
	private static final String TAG = "SignalsUploadService";
	private static final String KEY_SOURCE = "source";
	private static final MediaType MEDIA_TYPE_ZIP = MediaType.parse("application/zip");
	private static Thread thread;
	private static boolean isUploading = false;

	public static boolean isUploading() {
		return isUploading;
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
	public static Failure<String> requestUpload(@NonNull Context c, UploadScheduleSource source) {
		if (source.equals(UploadScheduleSource.NONE))
			throw new InvalidParameterException("Upload source can't be NONE.");
		else if (isUploading)
			return new Failure<>(c.getString(R.string.error_upload_in_progress));

		SharedPreferences sp = Preferences.get(c);
		int autoUpload = sp.getInt(Preferences.AUTO_UPLOAD, 1);
		if (autoUpload != 0 || source.equals(UploadScheduleSource.USER)) {
			JobScheduler scheduler = ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE));
			JobInfo.Builder jb = new JobInfo.Builder(Preferences.UPLOAD_JOB, new ComponentName(c, UploadService.class));
			jb.setPersisted(true);
			if (source.equals(UploadScheduleSource.USER)) {
				jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
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
			pb.putInt(KEY_SOURCE, source.ordinal());
			jb.setExtras(pb);
			scheduler.schedule(jb.build());
			updateUploadScheduleSource(c, source);
		}
		return new Failure<>();
	}

	/**
	 * Uploads data to server.
	 *
	 * @param file file to be uploaded
	 */
	private boolean upload(final File file) {
		if (file == null)
			throw new InvalidParameterException("file is null");
		String imei = Assist.getImei();
		RequestBody formBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("imei", imei)
				.addFormDataPart("file", Network.generateVerificationString(imei, file.length()), RequestBody.create(MEDIA_TYPE_ZIP, file))
				.build();
		Request request = Network.request(Network.URL_DATA_UPLOAD, formBody);
		try {
			Response response = new OkHttpClient().newCall(request).execute();
			int code = response.code();
			boolean isSuccessful = response.isSuccessful();
			response.close();
			if (isSuccessful) {
				if (!file.delete()) {
					FirebaseCrash.report(new IOException("Failed to delete file " + file.getName() + ". This should never happen."));
				}
				return true;
			}
			FirebaseCrash.report(new Throwable("Upload failed " + code));
			return false;
		} catch (IOException e) {
			FirebaseCrash.report(e);
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
			isUploading = true;
			thread = new Thread(() -> {
				String[] files = DataStore.getDataFileNames(source.equals(UploadScheduleSource.USER));
				if (files == null) {
					FirebaseCrash.report(new Throwable("No files found. This should not happen. Upload initiated by " + source.name()));
					DataStore.onUpload(-1);
					isUploading = false;
					return;
				} else {
					if (source.equals(UploadScheduleSource.USER))
						DataStore.closeUploadFile(files[files.length - 1]);
					String zipName = "up" + System.currentTimeMillis();
					File f = Compress.zip(getFilesDir().getAbsolutePath(), files, zipName);
					if (upload(f)) {
						for (String file : files)
							DataStore.deleteFile(file);
						DataStore.onUpload(100);
					} else {
						DataStore.onUpload(-1);
						requestUpload(c, source);
					}
				}

				DataStore.cleanup();
				DataStore.recountDataSize();
				isUploading = false;
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
		Preferences.get(this).edit().putInt(Preferences.SCHEDULED_UPLOAD, UploadScheduleSource.NONE.ordinal()).apply();
		return uploadAll(UploadScheduleSource.values()[jobParameters.getExtras().getInt(KEY_SOURCE)]);
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (thread != null && thread.isAlive())
			thread.interrupt();
		DataStore.cleanup();
		DataStore.recountDataSize();
		isUploading = false;
		return true;
	}

	public enum UploadScheduleSource {
		NONE,
		BACKGROUND,
		USER
	}
}
