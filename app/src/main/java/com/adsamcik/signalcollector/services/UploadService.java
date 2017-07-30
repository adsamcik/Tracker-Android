package com.adsamcik.signalcollector.services;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Compress;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.Failure;
import com.adsamcik.signalcollector.file.FileStore;
import com.adsamcik.signalcollector.utility.Preferences;
import com.adsamcik.signalcollector.file.DataStore;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.network.Signin;
import com.google.firebase.crash.FirebaseCrash;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.InvalidParameterException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.Response;

public class UploadService extends JobService {
	private static final String TAG = "SignalsUploadService";
	private static final String KEY_SOURCE = "source";
	private static final MediaType MEDIA_TYPE_ZIP = MediaType.parse("application/zip");
	private static final int MIN_NO_ACTIVITY_DELAY = Assist.MINUTE_IN_MILLISECONDS * 20;

	private static boolean isUploading = false;
	private JobWorker worker;

	public static boolean isUploading() {
		return isUploading;
	}

	public static UploadScheduleSource getUploadScheduled(@NonNull Context context) {
		return UploadScheduleSource.values()[Preferences.get(context).getInt(Preferences.PREF_SCHEDULED_UPLOAD, 0)];
	}

	/**
	 * Requests upload
	 * Call this when you want to auto-upload
	 *
	 * @param context Non-null context
	 * @param source  Source that started the upload
	 */
	public static Failure<String> requestUpload(@NonNull Context context, UploadScheduleSource source) {
		if (source.equals(UploadScheduleSource.NONE))
			throw new InvalidParameterException("Upload source can't be NONE.");
		else if (isUploading)
			return new Failure<>(context.getString(R.string.error_upload_in_progress));

		if (hasEnoughData(source)) {
			SharedPreferences sp = Preferences.get(context);
			int autoUpload = sp.getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD);
			if (autoUpload != 0 || source.equals(UploadScheduleSource.USER)) {
				JobInfo.Builder jb = prepareBuilder(context, source);
				addNetworkTypeRequest(context, source, jb);

				JobScheduler scheduler = ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE));
				assert scheduler != null;
				if (scheduler.schedule(jb.build()) == JobScheduler.RESULT_FAILURE)
					return new Failure<>(context.getString(R.string.error_during_upload_scheduling));
				updateUploadScheduleSource(context, source);
				Network.cloudStatus = CloudStatus.SYNC_SCHEDULED;

				return new Failure<>();
			}
			return new Failure<>(context.getString(R.string.error_during_upload_scheduling));
		}
		return new Failure<>(context.getString(R.string.error_not_enough_data));
	}

	/**
	 * Requests scheduling of upload
	 *
	 * @param context context
	 */
	public static void requestUploadSchedule(@NonNull Context context) {
		if (hasEnoughData(UploadScheduleSource.BACKGROUND)) {
			JobInfo.Builder jb = prepareBuilder(context, UploadScheduleSource.BACKGROUND);
			jb.setMinimumLatency(MIN_NO_ACTIVITY_DELAY);
			addNetworkTypeRequest(context, UploadScheduleSource.BACKGROUND, jb);
			updateUploadScheduleSource(context, UploadScheduleSource.BACKGROUND);

			JobScheduler scheduler = ((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE));
			assert scheduler != null;
			scheduler.schedule(jb.build());
		}
	}

	private static JobInfo.Builder prepareBuilder(@NonNull Context context, UploadScheduleSource source) {
		JobInfo.Builder jobBuilder = new JobInfo.Builder(Preferences.UPLOAD_JOB, new ComponentName(context, UploadService.class));
		jobBuilder.setPersisted(true);
		PersistableBundle pb = new PersistableBundle(1);
		pb.putInt(KEY_SOURCE, source.ordinal());
		jobBuilder.setExtras(pb);
		return jobBuilder;
	}

	private static void addNetworkTypeRequest(@NonNull Context context, @NonNull UploadScheduleSource source, @NonNull JobInfo.Builder jobBuilder) {
		if (source.equals(UploadScheduleSource.USER)) {
			jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
		} else {
			if (Preferences.get(context).getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD) == 2) {
				//todo improve roaming handling
				if (Build.VERSION.SDK_INT >= 24)
					jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
				else
					jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
			} else
				jobBuilder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
		}
	}

	private static boolean hasEnoughData(@NonNull UploadScheduleSource source) {
		switch (source) {
			case BACKGROUND:
				return DataStore.sizeOfData() >= Constants.MIN_BACKGROUND_UPLOAD_FILE_SIZE;
			case USER:
				return DataStore.sizeOfData() >= Constants.MIN_USER_UPLOAD_FILE_SIZE;
			default:
				return false;
		}
	}

	private static void updateUploadScheduleSource(@NonNull Context context, UploadScheduleSource uss) {
		Preferences.get(context).edit().putInt(Preferences.PREF_SCHEDULED_UPLOAD, uss.ordinal()).apply();
	}

	@Override
	public boolean onStartJob(JobParameters jobParameters) {
		Preferences.get(this).edit().putInt(Preferences.PREF_SCHEDULED_UPLOAD, UploadScheduleSource.NONE.ordinal()).apply();
		UploadScheduleSource scheduleSource = UploadScheduleSource.values()[jobParameters.getExtras().getInt(KEY_SOURCE)];
		if(scheduleSource != UploadScheduleSource.NONE)
			throw new RuntimeException("Source cannot be null");

		if (!hasEnoughData(scheduleSource))
			return false;

		DataStore.onUpload(0);
		final Context c = getApplicationContext();
		if (!Assist.isInitialized())
			Assist.initialize(c);

		isUploading = true;
		worker = new JobWorker(getFilesDir().getAbsolutePath(), c, success -> {
			if (success)
				DataStore.onUpload(100);
			isUploading = false;
			jobFinished(jobParameters, !success);
		});
		worker.execute(jobParameters);
		return true;
	}

	@Override
	public boolean onStopJob(JobParameters jobParameters) {
		if (worker != null)
			worker.cancel(true);
		isUploading = false;
		return true;
	}

	public enum UploadScheduleSource {
		NONE,
		BACKGROUND,
		USER
	}

	private static class JobWorker extends AsyncTask<JobParameters, Void, Boolean> {
		private final String directory;
		private final WeakReference<Context> context;

		private File tempZipFile = null;
		private Response response = null;
		private Call call = null;

		private final INonNullValueCallback<Boolean> callback;

		JobWorker(final String dir, final Context context, @Nullable INonNullValueCallback<Boolean> callback) {
			this.directory = dir;
			this.context = new WeakReference<>(context.getApplicationContext());
			this.callback = callback;
		}

		/**
		 * Uploads data to server.
		 *
		 * @param file file to be uploaded
		 */
		private boolean upload(final File file, String token, String userID) {
			if (file == null)
				throw new InvalidParameterException("file is null");
			else if (token == null) {
				FirebaseCrash.report(new Throwable("Token is null"));
				return false;
			}

			RequestBody formBody = new MultipartBody.Builder()
					.setType(MultipartBody.FORM)
					.addFormDataPart("file", Network.generateVerificationString(userID, file.length()), RequestBody.create(MEDIA_TYPE_ZIP, file))
					.build();
			try {
				call = Network.client(null, context.get()).newCall(Network.requestPOST(Network.URL_DATA_UPLOAD, formBody));
				response = call.execute();
				int code = response.code();
				boolean isSuccessful = response.isSuccessful();
				response.close();
				if (isSuccessful) {
					if (!FileStore.delete(file))
						FirebaseCrash.report(new IOException("Failed to delete file " + file.getName() + ". This should never happen."));
					return true;
				}
				if (code >= 500 || code == 403)
					FirebaseCrash.report(new Throwable("Upload failed " + code));
				return false;
			} catch (IOException e) {
				FirebaseCrash.report(e);
				return false;
			}
		}

		private class StringWrapper {
			StringWrapper() {
				string = null;
			}

			public synchronized String getString() {
				return string;
			}

			public synchronized void setString(String string) {
				this.string = string;
			}

			private String string;

		}

		@Override
		protected Boolean doInBackground(JobParameters... params) {
			UploadScheduleSource source = UploadScheduleSource.values()[params[0].getExtras().getInt(KEY_SOURCE)];
			String[] files = DataStore.getDataFileNames(context.get(), source.equals(UploadScheduleSource.USER) ? Constants.MIN_USER_UPLOAD_FILE_SIZE : Constants.MIN_BACKGROUND_UPLOAD_FILE_SIZE);
			if (files == null) {
				FirebaseCrash.report(new Throwable("No files found. This should not happen. Upload initiated by " + source.name()));
				DataStore.onUpload(-1);
				return false;
			} else {
				if (source.equals(UploadScheduleSource.USER))
					DataStore.closeUploadFile(context.get(), files[files.length - 1]);
				String zipName = "up" + System.currentTimeMillis();
				tempZipFile = Compress.zip(directory, files, zipName);
				if (tempZipFile == null)
					return false;

				final Lock lock = new ReentrantLock();
				final Condition callbackReceived = lock.newCondition();
				final StringWrapper token = new StringWrapper();
				final StringWrapper userID = new StringWrapper();

				Signin.getUserAsync(context.get(), value -> {
					lock.lock();
					if (value != null) {
						token.setString(value.token);
						userID.setString(value.id);
					}
					callbackReceived.signal();
					lock.unlock();
				});

				lock.lock();
				try {
					if (token.getString() == null)
						callbackReceived.await();
				} catch (InterruptedException e) {
					FirebaseCrash.report(e);
					return false;
				} finally {
					lock.unlock();
				}

				if (upload(tempZipFile, token.getString(), userID.getString())) {
					for (String file : files)
						DataStore.delete(context.get(), file);
					tempZipFile = null;
				} else {
					return false;
				}
			}

			DataStore.recountDataSize(context.get());
			return true;
		}

		@Override
		protected void onPostExecute(Boolean aBoolean) {
			super.onPostExecute(aBoolean);
			DataStore.cleanup(context.get());

			if (!aBoolean) {
				DataStore.onUpload(-1);
			}

			if (tempZipFile != null && !FileStore.delete(tempZipFile))
				FirebaseCrash.report(new IOException("Upload zip file was not deleted"));

			if (callback != null)
				callback.callback(aBoolean);
		}

		@Override
		protected void onCancelled() {
			DataStore.cleanup(context.get());
			DataStore.recountDataSize(context.get());
			if (tempZipFile != null)
				FileStore.delete(tempZipFile);

			if (call != null)
				call.cancel();

			super.onCancelled();
		}
	}
}
