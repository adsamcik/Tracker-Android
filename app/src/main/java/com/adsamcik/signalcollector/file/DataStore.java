package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.util.MalformedJsonException;
import android.util.Pair;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataStore {
	public static final String TAG = "SignalsDatastore";

	public static final String RECENT_UPLOADS_FILE = "recentUploads";
	public static final String DATA_FILE = "dataStore";
	public static final String PREF_DATA_FILE_INDEX = "saveFileID";
	private static final String PREF_COLLECTED_DATA_SIZE = "totalSize";

	private static ICallback onDataChanged;
	private static INonNullValueCallback<Integer> onUploadProgress;

	private static volatile long approxSize = -1;

	private static File getFolder(@NonNull Context context) {
		return context.getFilesDir();
	}

	private static File file(@NonNull Context context, @NonNull String fileName) {
		return FileStore.file(getFolder(context), fileName);
	}

	/**
	 * Call to invoke onDataChanged callback
	 */
	private static void onDataChanged() {
		if (Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED && sizeOfData() >= Constants.MIN_USER_UPLOAD_FILE_SIZE)
			Network.cloudStatus = CloudStatus.SYNC_AVAILABLE;
		else if (Network.cloudStatus == CloudStatus.SYNC_AVAILABLE && sizeOfData() < Constants.MIN_USER_UPLOAD_FILE_SIZE)
			Network.cloudStatus = CloudStatus.NO_SYNC_REQUIRED;

		if (onDataChanged != null)
			onDataChanged.callback();
	}

	/**
	 * Call to invoke onUploadProgress callback
	 *
	 * @param progress progress as int (0-100)
	 */
	public static void onUpload(int progress) {
		if (progress == 100)
			Network.cloudStatus = sizeOfData() >= Constants.MIN_USER_UPLOAD_FILE_SIZE ? CloudStatus.NO_SYNC_REQUIRED : CloudStatus.SYNC_AVAILABLE;
		else if (progress == -1 && sizeOfData() > 0)
			Network.cloudStatus = CloudStatus.SYNC_AVAILABLE;
		else
			Network.cloudStatus = CloudStatus.SYNC_IN_PROGRESS;

		if (onUploadProgress != null)
			onUploadProgress.callback(progress);
	}

	/**
	 * Sets callback which is called when saved data changes (new data, delete, update)
	 *
	 * @param callback callback
	 */
	public static void setOnDataChanged(ICallback callback) {
		onDataChanged = callback;
	}

	/**
	 * Sets callback which is called on upload progress with percentage completed as integer (0-100)
	 *
	 * @param callback callback
	 */
	public static void setOnUploadProgress(INonNullValueCallback<Integer> callback) {
		onUploadProgress = callback;
	}

	/**
	 * Generates array of all data files
	 *
	 * @param context               context
	 * @param lastFileSizeThreshold Include last datafile if it exceeds this size
	 * @return array of datafile names
	 */
	public static String[] getDataFileNames(@NonNull Context context, @IntRange(from = 0) int lastFileSizeThreshold) {
		int maxID = Preferences.get(context).getInt(PREF_DATA_FILE_INDEX, -1);
		if (maxID == -1)
			return new String[0];

		if (lastFileSizeThreshold > 0) {
			if (sizeOf(context, DATA_FILE + maxID) < lastFileSizeThreshold)
				maxID--;
		}

		String[] fileNames = new String[maxID + 1];
		for (int i = 0; i <= maxID; i++)
			fileNames[i] = DATA_FILE + i;
		return fileNames;
	}

	/**
	 * Closes file if not closed already
	 *
	 * @param fileName filename
	 */
	public static void closeUploadFile(@NonNull Context context, @NonNull String fileName) {
		File f = file(context, fileName);
		StringBuilder sb = FileStore.loadStringAsBuilder(f);
		if (sb != null && sb.charAt(sb.length() - 2) != ']')
			FileStore.saveString(f, "]}", true);
		SharedPreferences preferences = Preferences.get(context);
		int index = preferences.getInt(PREF_DATA_FILE_INDEX, -1);
		if (fileName.endsWith(Integer.toString(index)))
			Preferences.get(context).edit().putInt(PREF_DATA_FILE_INDEX, ++index).apply();
	}

	/**
	 * Move file
	 *
	 * @param fileName    original file name
	 * @param newFileName new file name
	 * @return success
	 */
	public static boolean rename(@NonNull Context context, String fileName, String newFileName) {
		return FileStore.rename(file(context, fileName), newFileName);
	}

	/**
	 * Delete file
	 *
	 * @param fileName file name
	 */
	public static void delete(@NonNull Context context, String fileName) {
		FileStore.delete(file(context, fileName));
	}

	/**
	 * Checks if file exists
	 *
	 * @param fileName file name
	 * @return existence of file
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean exists(@NonNull Context context, @NonNull String fileName) {
		return file(context, fileName).exists();
	}

	/**
	 * Handles any leftover files that could have been corrupted by some issue and reorders existing files
	 */
	public synchronized static void cleanup(@NonNull Context context) {
		final String tmpName = "5GeVPiYk6J";
		File[] files = getFolder(context).listFiles();
		Arrays.sort(files, (File a, File b) -> a.getName().compareTo(b.getName()));
		ArrayList<Pair<Integer, String>> renamedFiles = new ArrayList<>();
		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE)) {
				String tempFileName = tmpName + Integer.toString(renamedFiles.size());
				if (FileStore.rename(file, tempFileName))
					renamedFiles.add(new Pair<>(renamedFiles.size(), tempFileName));
			}
		}

		for (Pair<Integer, String> item : renamedFiles)
			rename(context, item.second, DATA_FILE + item.first);

		Preferences.get().edit().putInt(PREF_DATA_FILE_INDEX, renamedFiles.size() == 0 ? 0 : renamedFiles.size() - 1).apply();
	}

	/**
	 * Inspects all data files and returns the total size
	 *
	 * @return total size of data
	 */
	public static long recountDataSize(@NonNull Context context) {
		String[] fileNames = getDataFileNames(context, 0);
		if (fileNames == null)
			return 0;
		long size = 0;
		for (String fileName : fileNames)
			size += sizeOf(context, fileName);
		Preferences.get().edit().putLong(PREF_COLLECTED_DATA_SIZE, size).apply();
		if (onDataChanged != null && approxSize != size)
			onDataChanged();
		approxSize = size;
		return size;
	}

	/**
	 * Initializes approximate data size variable
	 */
	private static void initSizeOfData() {
		if (approxSize == -1)
			approxSize = Preferences.get().getLong(PREF_COLLECTED_DATA_SIZE, 0);
	}

	/**
	 * Gets saved size of data.
	 *
	 * @return returns saved data size from shared preferences.
	 */
	public static long sizeOfData() {
		initSizeOfData();
		return approxSize;
	}

	/**
	 * Increments approx size by value
	 *
	 * @param value value
	 */
	public static void incSizeOfData(long value) {
		initSizeOfData();
		approxSize += value;
	}

	/**
	 * @param fileName Name of file
	 * @return Size of file
	 */
	private static long sizeOf(@NonNull Context context, String fileName) {
		return file(context, fileName).length();
	}


	/**
	 * Clears all data files
	 */
	public static void clearAllData(@NonNull Context context) {
		SharedPreferences sp = Preferences.get();
		sp.edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).apply();
		approxSize = 0;
		File[] files = getFolder(context).listFiles();

		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE))
				FileStore.delete(file);
		}
		onDataChanged();

		Bundle bundle = new Bundle();
		bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings");
		FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.CLEARED_DATA_EVENT, bundle);
	}

	/**
	 * Recursively deletes all files in a directory
	 *
	 * @param file File or directory
	 * @return True if successfull
	 */
	public static boolean recursiveDelete(File file) {
		if (file.isDirectory()) {
			for (File f : file.listFiles())
				if (!recursiveDelete(f))
					return false;
		}
		return file.delete();
	}

	public enum SaveStatus {
		SAVING_FAILED,
		SAVING_SUCCESSFULL,
		SAVED_TO_NEW_FILE
	}

	/**
	 * Saves data to file. File is determined automatically.
	 *
	 * @param data json array to be saved, without [ at the beginning
	 * @return returns state value 2 - new file, saved succesfully, 1 - error during saving, 0 - no new file, saved successfully
	 */
	public synchronized static SaveStatus saveData(@NonNull Context context, @NonNull String data) {
		SharedPreferences sp = Preferences.get();
		SharedPreferences.Editor edit = sp.edit();

		int id = sp.getInt(PREF_DATA_FILE_INDEX, 0);
		boolean fileHasNoData;
		long fileSize = sizeOf(context, DATA_FILE + id);
		if (fileSize > Constants.MAX_DATA_FILE_SIZE) {
			FileStore.saveString(file(context, DATA_FILE + id), "]}", true);
			edit.putInt(PREF_DATA_FILE_INDEX, ++id);
			fileHasNoData = true;
			onDataChanged();
		} else
			fileHasNoData = fileSize == 0;


		if (fileHasNoData) {
			String userID = sp.getString(Preferences.PREF_USER_ID, null);
			if (userID == null || !FileStore.saveString(file(context, DATA_FILE + id), "{\"userID\":\"" + userID + "\"," +
					"\"model\":\"" + Build.MODEL +
					"\",\"manufacturer\":\"" + Build.MANUFACTURER +
					"\",\"api\":" + Build.VERSION.SDK_INT +
					",\"version\":" + BuildConfig.VERSION_CODE + "," +
					"\"data\":", false)) {
				return SaveStatus.SAVING_FAILED;
			}
		}

		try {
			if (!FileStore.saveAppendableJsonArray(file(context, DATA_FILE + id), data, true, fileHasNoData)) {
				if (fileSize > Constants.MAX_DATA_FILE_SIZE)
					edit.apply();
				return SaveStatus.SAVING_FAILED;
			}
		} catch (MalformedJsonException e) {
			if (fileSize > Constants.MAX_DATA_FILE_SIZE)
				edit.apply();
			FirebaseCrash.report(e);
			return SaveStatus.SAVING_FAILED;
		}

		int dataSize = data.getBytes(Charset.defaultCharset()).length;
		approxSize = sp.getLong(PREF_COLLECTED_DATA_SIZE, 0) + dataSize;
		edit.putLong(PREF_COLLECTED_DATA_SIZE, approxSize).apply();

		return fileHasNoData && id > 0 ? SaveStatus.SAVED_TO_NEW_FILE : SaveStatus.SAVING_SUCCESSFULL;
	}

	/**
	 * Removes all old recent uploads that are saved.
	 */
	public static synchronized void removeOldRecentUploads(@NonNull Context context) {
		SharedPreferences sp = Preferences.get(context);
		long oldestUpload = sp.getLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, -1);
		if (oldestUpload != -1) {
			long days = Assist.getAgeInDays(oldestUpload);
			if (days > 30) {
				Gson gson = new Gson();
				ArrayList<UploadStats> stats = gson.fromJson(FileStore.loadAppendableJsonArray(file(context, RECENT_UPLOADS_FILE)), new TypeToken<List<UploadStats>>() {
				}.getType());
				for (int i = 0; i < stats.size(); i++) {
					if (Assist.getAgeInDays(stats.get(i).time) > 30)
						stats.remove(i--);
				}

				if (stats.size() > 0)
					sp.edit().putLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, stats.get(0).time).apply();
				else
					sp.edit().remove(Preferences.PREF_OLDEST_RECENT_UPLOAD).apply();

				try {
					FileStore.saveAppendableJsonArray(file(context, RECENT_UPLOADS_FILE), gson.toJson(stats), false);
				} catch (Exception e) {
					FirebaseCrash.report(e);
				}
			}
		}
	}

	public static boolean saveString(@NonNull Context context, @NonNull String fileName, @NonNull String data, boolean append) {
		return FileStore.saveString(file(context, fileName), data, append);
	}

	public static <T> boolean saveJsonArrayAppend(@NonNull Context context, @NonNull String fileName, @NonNull T data, boolean append) {
		return saveJsonArrayAppend(context, fileName, new Gson().toJson(data), append);
	}

	public static boolean saveJsonArrayAppend(@NonNull Context context, @NonNull String fileName, @NonNull String data, boolean append) {
		try {
			return FileStore.saveAppendableJsonArray(file(context, fileName), data, append);
		} catch (MalformedJsonException e) {
			FirebaseCrash.report(e);
			return false;
		}
	}

	public static String loadString(@NonNull Context context, @NonNull String fileName) {
		return FileStore.loadString(file(context, fileName));
	}

	public static String loadAppendableJsonArray(@NonNull Context context, @NonNull String fileName) {
		return FileStore.loadAppendableJsonArray(file(context, fileName));
	}

	public static <T> T loadLastFromAppendableJsonArray(@NonNull Context context, @NonNull String fileName, @NonNull Class<T> tClass) {
		return FileStore.loadLastFromAppendableJsonArray(file(context, fileName), tClass);
	}
}
