package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.util.Log;

import com.adsamcik.signalcollector.interfaces.ICallback;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.data.UploadStats;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DataStore {
	public static final String TAG = "DATA-STORE";

	public static final String RECENT_UPLOADS_FILE = "recentUploads";
	private static final String DATA_FILE = "dataStore";
	private static final String KEY_FILE_ID = "saveFileID";
	private static final String KEY_SIZE = "totalSize";
	//1048576B = 1MB, 5242880B = 5MB, 2097152B = 2MB
	private static final int MAX_FILE_SIZE = 1048576;

	private static WeakReference<Context> contextWeak;

	private static Context getContext() {
		return contextWeak.get();
	}

	public static void setContext(Context c) {
		if (c != null)
			contextWeak = new WeakReference<>(c.getApplicationContext());
	}

	private static ICallback onDataChanged;
	private static IValueCallback<Integer> onUploadProgress;

	private static long approxSize = -1;


	/**
	 * Call to invoke onDataChanged callback
	 */
	private static void onDataChanged() {
		if (onDataChanged != null)
			onDataChanged.callback();
	}

	/**
	 * Call to invoke onUploadProgress callback
	 *
	 * @param progress progress as int (0-100)
	 */
	public static void onUpload(int progress) {
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
	public static void setOnUploadProgress(IValueCallback<Integer> callback) {
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
		if (maxID < 0)
			return null;
		if (!includeLast)
			maxID--;
		String[] fileNames = new String[maxID + 1];
		for (int i = 0; i <= maxID; i++)
			fileNames[i] = DATA_FILE + i;
		return fileNames;
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
		return new File(getContext().getFilesDir().getAbsolutePath() + "/" + fileName).exists();
	}

	/**
	 * Handles any leftover files that could have been corrupted by some issue and reorders existing files
	 */
	public static void cleanup() {
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
		sp.edit().remove(KEY_SIZE).remove(KEY_FILE_ID).remove(Preferences.SCHEDULED_UPLOAD).apply();
		approxSize = 0;
		File[] files = getContext().getFilesDir().listFiles();

		for (File file : files) {
			String name = file.getName();
			if (name.startsWith(DATA_FILE))
				deleteFile(name);
		}
		onDataChanged();
	}

	/**
	 * Saves data to file. File is determined automatically.
	 *
	 * @param data json array to be saved, without [ at the beginning
	 * @return returns state value 2 - new file, 1 - error during saving, 0 - no new file, saved successfully
	 */
	public static int saveData(String data) {
		SharedPreferences sp = Preferences.get();
		SharedPreferences.Editor edit = sp.edit();

		int id = sp.getInt(KEY_FILE_ID, 0);
		boolean newFile = false;

		if (sizeOf(DATA_FILE + id) > MAX_FILE_SIZE) {
			edit.putInt(KEY_FILE_ID, ++id);
			newFile = true;
			onDataChanged();
		}


		if (!saveJsonArrayAppend(DATA_FILE + id, data))
			return 1;

		int size = data.getBytes(Charset.defaultCharset()).length;
		edit.putLong(KEY_SIZE, sp.getLong(KEY_SIZE, 0) + size).apply();

		return newFile && id > 0 ? 2 : 0;
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
			e.printStackTrace();
			FirebaseCrash.report(e);
			return false;
		}
		return true;
	}

	/**
	 * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
	 *
	 * @param fileName Name of file
	 * @param data     Json data to be saved
	 * @return Failure
	 */
	public static boolean saveJsonArrayAppend(@NonNull String fileName, @NonNull String data) {
		StringBuilder sb = new StringBuilder(data);
		if (sb.charAt(0) == '[')
			sb.setCharAt(0, ',');
		else
			sb.insert(0, ',');

		if (sb.charAt(sb.length() - 1) == ']')
			sb.deleteCharAt(sb.length() - 1);

		data = sb.toString();
		FileOutputStream outputStream;
		try {
			outputStream = getContext().openFileOutput(fileName, Context.MODE_APPEND);
			outputStream.write(data.getBytes());
			outputStream.close();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
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
			sb.setCharAt(0, '[');
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
		if (!exists(fileName))
			return null;

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
			e.printStackTrace();
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
		String out = "[";
		String data;
		for (Object anArray : array) {
			data = objectToJSON(anArray);
			if (!data.equals(""))
				out += data + ",";
		}

		if (out.length() > 1)
			out = out.substring(0, out.length() - 1);
		out += "]";
		return out;
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
		String out = "{";
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
					out += "\"" + field.getName() + "\":";
					out += data;
					out += ",";
				}
			} catch (Exception e) {
				e.printStackTrace();
				Log.e("Exception", field.getName() + " - " + e.getMessage());
			}
		}
		if (out.length() <= 1) return "";
		out = out.substring(0, out.length() - 1);
		out += "}";
		return out;
	}

	/**
	 * Removes all old recent uploads that are saved.
	 */
	public static void removeOldRecentUploads() {
		SharedPreferences sp = Preferences.get(contextWeak.get());
		long oldestUpload = sp.getLong(Preferences.OLDEST_RECENT_UPLOAD, -1);
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
					sp.edit().putLong(Preferences.OLDEST_RECENT_UPLOAD, stats.get(0).time).apply();
				else
					sp.edit().remove(Preferences.OLDEST_RECENT_UPLOAD).apply();

				DataStore.saveJsonArrayAppend(RECENT_UPLOADS_FILE, gson.toJson(stats));
			}
		}
	}
}
