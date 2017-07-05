package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.MalformedJsonException;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.data.UploadStats;
import com.adsamcik.signalcollector.network.Network;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataStore {
	public static final String TAG = "SignalsDatastore";

	public static final String RECENT_UPLOADS_FILE = "recentUploads";
	public static final String DATA_FILE = "dataStore";
	private static final String KEY_FILE_ID = "saveFileID";
	private static final String KEY_SIZE = "totalSize";
	//1048576B = 1MB, 5242880B = 5MB, 2097152B = 2MB
	private static final int MAX_FILE_SIZE = 1048576;

	private static WeakReference<Context> contextWeak;
	private static ICallback onDataChanged;
	private static INonNullValueCallback<Integer> onUploadProgress;

	private static volatile long approxSize = -1;

	private static Context getContext() {
		return contextWeak.get();
	}

	public static void setContext(Context c) {
		if (c != null)
			contextWeak = new WeakReference<>(c);
	}

	/**
	 * Call to invoke onDataChanged callback
	 */
	private static void onDataChanged() {
		if (Network.cloudStatus == CloudStatus.NO_SYNC_REQUIRED && sizeOfData() > 0)
			Network.cloudStatus = CloudStatus.SYNC_REQUIRED;
		else if (Network.cloudStatus == CloudStatus.SYNC_REQUIRED && sizeOfData() == 0)
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
			Network.cloudStatus = sizeOfData() == 0 ? CloudStatus.NO_SYNC_REQUIRED : CloudStatus.SYNC_REQUIRED;
		else if (progress == -1 && sizeOfData() > 0)
			Network.cloudStatus = CloudStatus.SYNC_REQUIRED;
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
	 * @param includeLast Include last file (last file is almost always not complete)
	 * @return Returns data file names
	 */
	public static String[] getDataFileNames(boolean includeLast) {
		int maxID = Preferences.get(getContext()).getInt(KEY_FILE_ID, -1);
		if ((!includeLast && --maxID < 0) || maxID < 0)
			return null;
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
	public static void closeUploadFile(String fileName) {
		StringBuilder sb = loadStringAsBuilder(fileName);
		if (sb != null && sb.charAt(sb.length() - 2) != ']')
			saveString(fileName, sb.append("]}").toString());
	}

	/**
	 * Move file
	 *
	 * @param fileName    original file name
	 * @param newFileName new file name
	 * @return success
	 */
	public static boolean renameFile(String fileName, String newFileName) {
		String dir = getContext().getFilesDir().getPath();
		return new File(dir, fileName).renameTo(new File(dir, newFileName));
	}

	/**
	 * Delete file
	 *
	 * @param fileName file name
	 */
	public static void deleteFile(String fileName) {
		getContext().deleteFile(fileName);
	}

	/**
	 * Checks if file exists
	 *
	 * @param fileName file name
	 * @return existence of file
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean exists(String fileName) {
		return new File(getContext().getFilesDir().getAbsolutePath() + File.separatorChar + fileName).exists();
	}

	/**
	 * Handles any leftover files that could have been corrupted by some issue and reorders existing files
	 */
	public synchronized static void cleanup() {
		File[] files = getContext().getFilesDir().listFiles();
		Arrays.sort(files, (File a, File b) -> a.getName().compareTo(b.getName()));
		ArrayList<String> renamedFiles = new ArrayList<>();
		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE)) {
				renameFile(name, Integer.toString(renamedFiles.size()));
				renamedFiles.add(Integer.toString(renamedFiles.size()));
			}
		}

		for (String item : renamedFiles)
			renameFile(item, DATA_FILE + item);

		Preferences.get().edit().putInt(KEY_FILE_ID, renamedFiles.size() == 0 ? 0 : renamedFiles.size() - 1).apply();
	}

	/**
	 * Inspects all data files and returns the total size
	 *
	 * @return total size of data
	 */
	public static long recountDataSize() {
		String[] fileNames = getDataFileNames(true);
		if (fileNames == null)
			return 0;
		long size = 0;
		for (String fileName : fileNames)
			size += sizeOf(fileName);
		Preferences.get().edit().putLong(KEY_SIZE, size).apply();
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
			approxSize = Preferences.get().getLong(KEY_SIZE, 0);
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
	private static long sizeOf(String fileName) {
		return new File(getContext().getFilesDir().getPath(), fileName).length();
	}


	/**
	 * Clears all data files
	 */
	public static void clearAllData() {
		SharedPreferences sp = Preferences.get();
		sp.edit().remove(KEY_SIZE).remove(KEY_FILE_ID).remove(Preferences.PREF_SCHEDULED_UPLOAD).apply();
		approxSize = 0;
		File[] files = getContext().getFilesDir().listFiles();

		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE))
				deleteFile(name);
		}
		onDataChanged();

		Bundle bundle = new Bundle();
		bundle.putString(FirebaseAssist.PARAM_SOURCE, "settings");
		FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAssist.CLEARED_DATA_EVENT, bundle);
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

	/**
	 * Tries to delete file 5 times.
	 * After every unsuccessfull try there is 50ms sleep so you should ensure that this function does not run on UI thread.
	 *
	 * @param file file to delete
	 * @return true if file was deleted, false otherwise
	 */
	public static boolean retryDelete(File file) {
		return retryDelete(file, 5);
	}

	/**
	 * Tries to delete file multiple times based on {@code maxRetryCount}.
	 * After every unsuccessfull try there is 50ms sleep so you should ensure that this function does not run on UI thread.
	 *
	 * @param file          file to delete
	 * @param maxRetryCount maximum retry count
	 * @return true if file was deleted, false otherwise
	 */
	public static boolean retryDelete(File file, int maxRetryCount) {
		if (file == null)
			throw new InvalidParameterException("file is null");

		int retryCount = 0;
		for (; ; ) {
			if (!file.exists() || file.delete())
				return true;

			if (++retryCount < maxRetryCount)
				return false;

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				// Restore the interrupted status
				Thread.currentThread().interrupt();
			}
		}
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
	public synchronized static SaveStatus saveData(String data) {
		SharedPreferences sp = Preferences.get();
		SharedPreferences.Editor edit = sp.edit();

		int id = sp.getInt(KEY_FILE_ID, 0);
		boolean fileHasNoData;
		long fileSize = sizeOf(DATA_FILE + id);
		if (fileSize > MAX_FILE_SIZE) {
			saveStringAppend(DATA_FILE + id, "]}");
			edit.putInt(KEY_FILE_ID, ++id);
			fileHasNoData = true;
			onDataChanged();
		} else
			fileHasNoData = fileSize == 0;


		if (fileHasNoData) {
			String userID = sp.getString(Preferences.PREF_USER_ID, null);
			if (userID == null || !saveString(DATA_FILE + id, "{\"userID\":\"" + userID + "\"," +
					"\"model\":\"" + Build.MODEL +
					"\",\"manufacturer\":\"" + Build.MANUFACTURER +
					"\",\"api\":" + Build.VERSION.SDK_INT +
					",\"version\":" + BuildConfig.VERSION_CODE + "," +
					"\"data\":")) {
				return SaveStatus.SAVING_FAILED;
			}
		}

		try {
			if (!saveJsonArrayAppend(DATA_FILE + id, data, fileHasNoData)) {
				if (fileSize > MAX_FILE_SIZE)
					edit.apply();
				return SaveStatus.SAVING_FAILED;
			}
		} catch (MalformedJsonException e) {
			if (fileSize > MAX_FILE_SIZE)
				edit.apply();
			FirebaseCrash.report(e);
			return SaveStatus.SAVING_FAILED;
		}

		int dataSize = data.getBytes(Charset.defaultCharset()).length;
		approxSize = sp.getLong(KEY_SIZE, 0) + dataSize;
		edit.putLong(KEY_SIZE, approxSize).apply();

		return fileHasNoData && id > 0 ? SaveStatus.SAVED_TO_NEW_FILE : SaveStatus.SAVING_SUCCESSFULL;
	}


	/**
	 * Saves string to file
	 *
	 * @param fileName file name
	 * @param data     string data
	 */
	@SuppressWarnings("SameParameterValue")
	public static boolean saveString(String fileName, String data) {
		try {
			FileOutputStream outputStream = getContext().openFileOutput(fileName, Context.MODE_PRIVATE);
			OutputStreamWriter osw = new OutputStreamWriter(outputStream);
			osw.write(data);
			osw.close();
		} catch (Exception e) {
			FirebaseCrash.report(e);
			return false;
		}
		return true;
	}

	public static boolean saveStringAppend(String fileName, String data) {
		try (FileOutputStream outputStream = getContext().openFileOutput(fileName, Context.MODE_APPEND)) {
			outputStream.getChannel().lock();
			OutputStreamWriter osw = new OutputStreamWriter(outputStream);
			osw.write(data);
			osw.close();
		} catch (Exception e) {
			FirebaseCrash.report(e);
			return false;
		}
		return true;
	}

	/**
	 * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
	 *
	 * @param fileName File name
	 * @param data     Json array to append
	 * @return Failure
	 * @throws MalformedJsonException Thrown when json array is in incorrect format
	 */
	public static boolean saveJsonArrayAppend(@NonNull String fileName, @NonNull String data) throws MalformedJsonException {
		return saveJsonArrayAppend(fileName, data, false, sizeOf(fileName) == 0);
	}

	/**
	 * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
	 * Allows custom empty array detection.
	 *
	 * @param fileName     Name of file
	 * @param data         Json array to append
	 * @param isArrayEmpty Does file already contain some data?
	 * @return Failure
	 * @throws MalformedJsonException Thrown when json array is in incorrect format
	 */
	public static boolean saveJsonArrayAppend(@NonNull String fileName, @NonNull String data, boolean isArrayEmpty) throws MalformedJsonException {
		return saveJsonArrayAppend(fileName, data, false, isArrayEmpty);
	}

	/**
	 * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
	 * Allows file overriding and custom empty array detection.
	 *
	 * @param fileName     Name of file
	 * @param data         Json array to append
	 * @param override     Should existing file be overriden with current data
	 * @param isArrayEmpty Does file already contain some data?
	 * @return Failure
	 * @throws MalformedJsonException Thrown when json array is in incorrect format
	 */
	public static boolean saveJsonArrayAppend(@NonNull String fileName, @NonNull String data, boolean override, boolean isArrayEmpty) throws MalformedJsonException {
		StringBuilder sb = new StringBuilder(data);
		if (sb.charAt(0) == ',')
			throw new MalformedJsonException("Json starts with ','. That is not right.");
		char firstChar = override || isArrayEmpty ? '[' : ',';
		switch (firstChar) {
			case ',':
				if (sb.charAt(0) == '[')
					sb.setCharAt(0, ',');
				else
					sb.insert(0, ',');
				break;
			case '[':
				if (sb.charAt(0) == '{')
					sb.insert(0, '[');
		}

		if (sb.charAt(sb.length() - 1) == ']')
			sb.deleteCharAt(sb.length() - 1);

		data = sb.toString();
		try (FileOutputStream outputStream = getContext().openFileOutput(fileName, override ? Context.MODE_PRIVATE : Context.MODE_APPEND)) {
			outputStream.getChannel().lock();
			outputStream.write(data.getBytes());
			outputStream.close();
			return true;
		} catch (Exception e) {
			FirebaseCrash.report(e);
			return false;
		}
	}

	/**
	 * Loads json array that was saved with append method
	 *
	 * @param fileName file name
	 * @return proper json array
	 */
	public static String loadJsonArrayAppend(String fileName) {
		StringBuilder sb = loadStringAsBuilder(fileName);
		if (sb != null && sb.length() != 0) {
			if (sb.charAt(sb.length() - 1) != ']')
				sb.append(']');
			return sb.toString();
		}
		return null;
	}

	/**
	 * Loads whole json array and than finds last object and converts it to java object
	 *
	 * @param fileName file name
	 * @param tClass   class of the resulting object
	 * @return last object of json array or null
	 */
	public static <T> T loadLastObjectJsonArrayAppend(String fileName, Class<T> tClass) {
		StringBuilder sb = loadStringAsBuilder(fileName);
		if (sb == null)
			return null;
		for (int i = sb.length() - 1; i >= 0; i--) {
			if (sb.charAt(i) == '{') {
				try {
					return new Gson().fromJson(sb.substring(i), tClass);
				} catch (JsonSyntaxException e) {
					FirebaseCrash.report(e);
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Load string file as StringBuilder
	 *
	 * @param fileName file name
	 * @return content of file as StringBuilder
	 */
	public static StringBuilder loadStringAsBuilder(@NonNull String fileName) {
		if (!exists(fileName)) {
			FirebaseCrash.log("Tried loading file that does not exists");
			return null;
		}

		try {
			FileInputStream fis = getContext().openFileInput(fileName);
			InputStreamReader isr = new InputStreamReader(fis);
			BufferedReader br = new BufferedReader(isr);
			String receiveString;
			StringBuilder stringBuilder = new StringBuilder();

			while ((receiveString = br.readLine()) != null)
				stringBuilder.append(receiveString);

			isr.close();
			return stringBuilder;
		} catch (Exception e) {
			FirebaseCrash.report(e);
			return null;
		}
	}

	/**
	 * Converts loadStringAsBuilder to string and handles nulls
	 *
	 * @param fileName file name
	 * @return content of file (empty if file has no content or does not exists)
	 */
	@SuppressWarnings("SameParameterValue")
	public static String loadString(String fileName) {
		StringBuilder sb = loadStringAsBuilder(fileName);
		if (sb != null)
			return sb.toString();
		else
			return "";
	}


	/**
	 * Converts array to json using reflection
	 *
	 * @param array array
	 * @return json array
	 */
	public static String arrayToJSON(Object[] array) {
		if (array == null)
			return "";
		StringBuilder out = new StringBuilder("[");
		String data;
		for (Object anArray : array) {
			data = objectToJSON(anArray);
			if (!data.equals(""))
				out.append(data).append(",");
		}

		if (out.length() > 1)
			out = new StringBuilder(out.substring(0, out.length() - 1));
		out.append("]");
		return out.toString();
	}

	/**
	 * Converts objects to json using reflection. Can handle most used primitive types, strings, arrays and other objects.
	 *
	 * @param o object
	 * @return json object
	 */
	public static String objectToJSON(Object o) {
		if (o == null) return "";
		Class c = o.getClass();
		Field[] fields = c.getFields();
		StringBuilder out = new StringBuilder("{");
		for (Field field : fields) {
			try {
				if (field == null || Modifier.isStatic(field.getModifiers()))
					continue;
				String typeName = field.getType().getSimpleName();
				String data = "";
				if (field.getType().isArray())
					data = arrayToJSON((Object[]) field.get(o));
				else if (typeName.equals("int")) {
					int val = field.getInt(o);
					if (val != 0)
						data = Integer.toString(val);
				} else if (typeName.equals("String")) {
					String val = (String) field.get(o);
					if (val != null)
						data = "\"" + val.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
				} else if (typeName.equals("double")) {
					double val = field.getDouble(o);
					if (val != 0)
						data = Double.toString(val);
				} else if (typeName.equals("long")) {
					long val = field.getLong(o);
					if (val != 0)
						data = Long.toString(val);
				} else if (typeName.equals("float")) {
					float val = field.getFloat(o);
					if (val != 0)
						data = Float.toString(val);
				} else if (typeName.equals("short")) {
					short val = field.getShort(o);
					if (val != 0)
						data = Short.toString(val);
				} else if (typeName.equals("boolean"))
					data = Boolean.toString(field.getBoolean(o));
				else if (!field.getType().isPrimitive())
					data = objectToJSON(field.get(o));
				else {
					FirebaseCrash.report(new Throwable("Unknown type " + typeName + " - " + field.getName()));
					Log.e("type", "Unknown type " + typeName + " - " + field.getName());
				}

				if (!data.equals("")) {
					out.append("\"").append(field.getName()).append("\":");
					out.append(data);
					out.append(",");
				}
			} catch (Exception e) {
				e.printStackTrace();
				Log.e("Exception", field.getName() + " - " + e.getMessage());
			}
		}
		if (out.length() <= 1) return "";
		out = new StringBuilder(out.substring(0, out.length() - 1));
		out.append("}");
		return out.toString();
	}

	/**
	 * Removes all old recent uploads that are saved.
	 */
	public static synchronized void removeOldRecentUploads() {
		SharedPreferences sp = Preferences.get(contextWeak.get());
		long oldestUpload = sp.getLong(Preferences.PREF_OLDEST_RECENT_UPLOAD, -1);
		if (oldestUpload != -1) {
			long days = Assist.getAgeInDays(oldestUpload);
			if (days > 30) {
				Gson gson = new Gson();
				ArrayList<UploadStats> stats = gson.fromJson(DataStore.loadJsonArrayAppend(RECENT_UPLOADS_FILE), new TypeToken<List<UploadStats>>() {
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
					DataStore.saveJsonArrayAppend(RECENT_UPLOADS_FILE, gson.toJson(stats), true, false);
				} catch (Exception e) {
					FirebaseCrash.report(e);
				}
			}
		}
	}
}
