package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.MalformedJsonException;
import android.util.Pair;

import com.adsamcik.signalcollector.data.RawData;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.network.Network;
import com.adsamcik.signalcollector.services.UploadService;
import com.adsamcik.signalcollector.signin.Signin;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Constants;
import com.adsamcik.signalcollector.utility.FirebaseAssist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataStore {
	public static final String TAG = "SignalsDatastore";

	public static final String RECENT_UPLOADS_FILE = "recentUploads";
	public static final String DATA_FILE = "dataStore";
	public static final String DATA_CACHE_FILE = "dataCacheFile";
	public static final String PREF_DATA_FILE_INDEX = "saveFileID";
	public static final String PREF_CACHE_FILE_INDEX = "saveCacheID";
	private static final String PREF_COLLECTED_DATA_SIZE = "totalSize";

	private static ICallback onDataChanged;
	private static INonNullValueCallback<Integer> onUploadProgress;

	private static volatile long approxSize = -1;
	private static volatile int collectionsOnDevice = -1;

	private static DataFile currentDataFile = null;
	private static int collectionInDataFile = 0;

	public static File getDir(@NonNull Context context) {
		return context.getFilesDir();
	}

	public static File file(@NonNull Context context, @NonNull String fileName) {
		return FileStore.file(getDir(context), fileName);
	}

	/**
	 * Call to invoke onDataChanged callback
	 */
	private static void onDataChanged(@NonNull final Context context) {
		if (Network.INSTANCE.getCloudStatus() == CloudStatus.NO_SYNC_REQUIRED && sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE)
			Network.INSTANCE.setCloudStatus(CloudStatus.SYNC_AVAILABLE);
		else if (Network.INSTANCE.getCloudStatus() == CloudStatus.SYNC_AVAILABLE && sizeOfData(context) < Constants.MIN_USER_UPLOAD_FILE_SIZE)
			Network.INSTANCE.setCloudStatus(CloudStatus.NO_SYNC_REQUIRED);

		if (onDataChanged != null)
			onDataChanged.callback();
	}

	/**
	 * Call to invoke onUploadProgress callback
	 *
	 * @param progress progress as int (0-100)
	 */
	public static void onUpload(@NonNull final Context context, final int progress) {
		if (progress == 100)
			Network.INSTANCE.setCloudStatus(sizeOfData(context) >= Constants.MIN_USER_UPLOAD_FILE_SIZE ? CloudStatus.SYNC_AVAILABLE : CloudStatus.NO_SYNC_REQUIRED);
		else if (progress == -1 && sizeOfData(context) > 0)
			Network.INSTANCE.setCloudStatus(CloudStatus.SYNC_AVAILABLE);
		else
			Network.INSTANCE.setCloudStatus(CloudStatus.SYNC_IN_PROGRESS);

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
	public static File[] getDataFiles(@NonNull Context context, @IntRange(from = 0) int lastFileSizeThreshold) {
		return getDir(context).listFiles((file, s) -> s.startsWith(DATA_FILE));
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
		if (!FileStore.delete(file(context, fileName)))
			FirebaseCrash.report(new RuntimeException("Failed to delete " + fileName));
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
		File[] files = getDir(context).listFiles();
		Arrays.sort(files, (File a, File b) -> a.getName().compareTo(b.getName()));
		ArrayList<Pair<Integer, String>> renamedFiles = new ArrayList<>();
		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE)) {
				String tempFileName = tmpName + DataFile.getCollectionCount(file);
				if (FileStore.rename(file, tempFileName))
					renamedFiles.add(new Pair<>(renamedFiles.size(), tempFileName));
			}
		}

		for (Pair<Integer, String> item : renamedFiles)
			rename(context, item.second, DATA_FILE + item.first + DataFile.SEPARATOR + item.second.substring(tmpName.length()));

		Preferences.get(context).edit().putInt(PREF_DATA_FILE_INDEX, renamedFiles.size() == 0 ? 0 : renamedFiles.size() - 1).apply();
		currentDataFile = null;
	}

	/**
	 * Inspects all data files and returns the total size
	 *
	 * @return total size of data
	 */
	public static long recountData(@NonNull Context context) {
		File[] files = getDataFiles(context, 0);
		if (files == null)
			return 0;
		long size = 0;
		collectionsOnDevice = 0;
		for (File file : files) {
			size += file.length();
			collectionsOnDevice += DataFile.getCollectionCount(file);
		}
		
		Preferences.get(context).edit().putLong(PREF_COLLECTED_DATA_SIZE, size).apply();
		if (onDataChanged != null && approxSize != size)
			onDataChanged(context);
		approxSize = size;
		return size;
	}

	/**
	 * Initializes approximate data size variable
	 */
	private static void initSizeOfData(@NonNull final Context context) {
		if (approxSize == -1) {
			approxSize = Preferences.get(context).getLong(PREF_COLLECTED_DATA_SIZE, 0);
			collectionsOnDevice = Preferences.get(context).getInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, 0);
		}
	}

	/**
	 * Sets collection count
	 * This method needs to be called with caution. Incorrect calling can cause smart uploader to malfunction.
	 * Since issues would not affect tracking, it is public, because there needs to be some way to update collection count from upload function.
	 *
	 * @param context context
	 * @param count   count of collections
	 */
	public static void setCollections(@NonNull final Context context, final int count) {
		Preferences.get(context).edit().putInt(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD, count).apply();
		collectionsOnDevice = count;
	}

	/**
	 * Gets saved size of data.
	 *
	 * @return returns saved data size from shared preferences.
	 */
	public static long sizeOfData(@NonNull final Context context) {
		initSizeOfData(context);
		return approxSize;
	}

	/**
	 * Gets saved size of data.
	 *
	 * @return returns saved data size from shared preferences.
	 */
	public static int collectionCount(@NonNull final Context context) {
		initSizeOfData(context);
		return collectionsOnDevice;
	}

	/**
	 * Increments approx size by value
	 *
	 * @param value value
	 */
	public static void incData(@NonNull final Context context, final long value, final int count) {
		initSizeOfData(context);
		approxSize += value;
		collectionsOnDevice += count;
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
		currentDataFile = null;
		SharedPreferences sp = Preferences.get(context);
		sp.edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).remove(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD).apply();
		approxSize = 0;
		collectionsOnDevice = 0;
		File[] files = getDir(context).listFiles();

		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE))
				if (!FileStore.delete(file))
					FirebaseCrash.report(new RuntimeException("Failed to delete " + file.getName()));
		}
		onDataChanged(context);

		Bundle bundle = new Bundle();
		bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings");
		FirebaseAnalytics.getInstance(context).logEvent(FirebaseAssist.CLEARED_DATA_EVENT, bundle);
	}

	public static void clearAll(@NonNull Context context) {
		currentDataFile = null;
		FileStore.clearFolder(getDir(context));
		Preferences.get(context).edit().remove(PREF_COLLECTED_DATA_SIZE).remove(PREF_DATA_FILE_INDEX).remove(Preferences.PREF_SCHEDULED_UPLOAD).remove(Preferences.PREF_COLLECTIONS_SINCE_LAST_UPLOAD).apply();
		approxSize = 0;
		collectionsOnDevice = 0;

		onDataChanged(context);
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
		SAVE_FAILED,
		SAVE_SUCCESS,
		SAVE_SUCCESS_FILE_DONE
	}

	public static DataFile getCurrentDataFile(@NonNull Context context) {
		if (currentDataFile == null) {
			String userID = Signin.Companion.getUserID(context);
			updateCurrentData(context, userID == null ? DataFile.FileType.CACHE : DataFile.FileType.STANDARD, userID);
		}
		return currentDataFile;
	}

	private synchronized static void updateCurrentData(@NonNull Context context, @DataFile.FileType int type, @Nullable String userID) {
		String dataFile;
		String preference;

		if (type == DataFile.FileType.STANDARD && userID == null) {
			type = DataFile.FileType.CACHE;
			FirebaseCrash.report(new Throwable("Wrong data file type"));
		}

		switch (type) {
			case DataFile.FileType.CACHE:
				dataFile = DATA_CACHE_FILE;
				preference = PREF_CACHE_FILE_INDEX;
				break;
			case DataFile.FileType.STANDARD:
				dataFile = DATA_FILE;
				preference = PREF_DATA_FILE_INDEX;
				break;
			default:
				FirebaseCrash.report(new Throwable("Unknown type " + type));
				return;
		}

		if (currentDataFile == null || currentDataFile.getType() != type || currentDataFile.isFull()) {
			String template = dataFile + Preferences.get(context).getInt(preference, 0);
			currentDataFile = new DataFile(FileStore.dataFile(getDir(context), template), template, userID, type);
		}
	}

	/**
	 * Saves rawData to file. File is determined automatically.
	 *
	 * @param rawData json array to be saved, without [ at the beginning
	 * @return returns state value 2 - new file, saved succesfully, 1 - error during saving, 0 - no new file, saved successfully
	 */
	public static SaveStatus saveData(@NonNull Context context, @NonNull RawData[] rawData) {
		String userID = Signin.Companion.getUserID(context);
		if (UploadService.isUploading || userID == null)
			updateCurrentData(context, DataFile.FileType.CACHE, userID);
		else
			updateCurrentData(context, DataFile.FileType.STANDARD, userID);
		return saveData(context, currentDataFile, rawData);
	}

	private synchronized static void writeTempData(@NonNull Context context) {
		String userId = Signin.Companion.getUserID(context);
		if (currentDataFile.getType() != DataFile.FileType.STANDARD || userId == null)
			return;

		File[] files = getDir(context).listFiles((file, s) -> s.startsWith(DATA_CACHE_FILE));
		if (files.length >= 1) {
			int newFileCount = files.length;
			int i = Preferences.get(context).getInt(PREF_DATA_FILE_INDEX, 0);

			if (files[0].length() + currentDataFile.size() <= 1.25 * Constants.MAX_DATA_FILE_SIZE) {
				String tempFileName = files[0].getName();
				int indexOf = tempFileName.indexOf(" - ");
				int collectionCount = 0;
				if (indexOf > 0) {
					collectionCount = Integer.parseInt(tempFileName.substring(indexOf + 3));
				}

				String data = FileStore.loadString(files[0]);
				assert data != null;
				if (!currentDataFile.addData(data, collectionCount))
					return;

				newFileCount--;
				i++;
				if (currentDataFile.isFull())
					currentDataFile.close();
			} else {
				currentDataFile.close();
			}

			if (files.length > 1) {
				int currentDataIndex = Preferences.get(context).getInt(PREF_DATA_FILE_INDEX, 0);
				Preferences.get(context).edit().putInt(PREF_DATA_FILE_INDEX, i + newFileCount).putInt(PREF_CACHE_FILE_INDEX, 0).apply();
				DataFile dataFile;
				for (; i < files.length; i++) {
					String data = FileStore.loadString(files[0]);
					assert data != null;
					String nameTemplate = DATA_FILE + (currentDataIndex + i);
					dataFile = new DataFile(FileStore.dataFile(getDir(context), nameTemplate), nameTemplate, userId, DataFile.FileType.STANDARD);
					if (!dataFile.addData(data, DataFile.getCollectionCount(files[0])))
						throw new RuntimeException();

					if (i < files.length - 1)
						dataFile.close();
				}
			}

			for (File f : files)
				if (!FileStore.delete(f))
					FirebaseCrash.report(new RuntimeException("Failed to delete " + f.getName()));
		}
	}

	private synchronized static SaveStatus saveData(@NonNull Context context, DataFile file, @NonNull RawData[] rawData) {
		long prevSize = file.size();

		if (file.getType() == DataFile.FileType.STANDARD)
			writeTempData(context);

		if (file.addData(rawData)) {
			long currentSize = file.size();
			SharedPreferences.Editor editor = Preferences.get(context).edit();
			editor.putLong(PREF_COLLECTED_DATA_SIZE, Preferences.get(context).getLong(PREF_COLLECTED_DATA_SIZE, 0) + currentSize - prevSize);

			if (currentSize > Constants.MAX_DATA_FILE_SIZE) {
				file.close();
				editor.putInt(file.getPreference(), Preferences.get(context).getInt(file.getPreference(), 0) + 1).apply();
				return SaveStatus.SAVE_SUCCESS_FILE_DONE;
			}

			editor.apply();
			return SaveStatus.SAVE_SUCCESS;
		}
		return SaveStatus.SAVE_FAILED;
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
				if(stats == null)
					 return;
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

	public static <T> boolean saveAppendableJsonArray(@NonNull Context context, @NonNull String fileName, @NonNull T data, boolean append) {
		return saveAppendableJsonArray(context, fileName, new Gson().toJson(data), append);
	}

	public static boolean saveAppendableJsonArray(@NonNull Context context, @NonNull String fileName, @NonNull String data, boolean append) {
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
