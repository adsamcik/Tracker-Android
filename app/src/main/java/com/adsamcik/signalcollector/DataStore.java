package com.adsamcik.signalcollector;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Looper;
import android.os.PersistableBundle;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.adsamcik.signalcollector.services.TrackerService;
import com.adsamcik.signalcollector.services.UploadService;
import com.google.firebase.crash.FirebaseCrash;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.SyncHttpClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;

import cz.msebera.android.httpclient.Header;

public class DataStore {
	public static final String TAG = "DATA-STORE";
	public static final String DATA_FILE = "dataStore";
	public static final String KEY_FILE_ID = "saveFileID";
	public static final String KEY_SIZE = "totalSize";
	public static final String KEY_IS_AUTOUPLOAD = "isAutoupload";

	//1048576 1MB, 5242880 5MB
	private static final int MAX_FILE_SIZE = 2097152;

	private static Context context;

	public static Context getContext() {
		return context;
	}

	public static void setContext(@NonNull Context c) {
		context = c;
	}

	/**
	 * Requests upload
	 * Call this when you want to auto-upload
	 *
	 * @param c Non-null context
	 */
	public static void requestUpload(@NonNull Context c) {
		SharedPreferences sp = Setting.getPreferences(c);
		int autoUpload = sp.getInt(Setting.AUTO_UPLOAD, 1);
		if (autoUpload != 0) {
			JobInfo.Builder jb = new JobInfo.Builder(Setting.UPLOAD_JOB, new ComponentName(context, UploadService.class));
			if (autoUpload == 2) {
				if (Build.VERSION.SDK_INT >= 24)
					jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NOT_ROAMING);
				else
					jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
			} else
				jb.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
			PersistableBundle pb = new PersistableBundle(1);
			pb.putInt(KEY_IS_AUTOUPLOAD, 1);
			jb.setExtras(pb);
			((JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).schedule(jb.build());
			sp.edit().putBoolean(Setting.SCHEDULED_UPLOAD, true).apply();
		}
	}

	/**
	 * Generates array of all data files
	 *
	 * @param includeLast Include last file (last file is almost always not complete)
	 * @return Returns data file names
	 */
	public static String[] getDataFileNames(boolean includeLast) {
		int maxID = Setting.getPreferences(context).getInt(KEY_FILE_ID, 0);
		if (!includeLast)
			maxID--;
		String[] fileNames = new String[maxID + 1];
		for (int i = 0; i <= maxID; i++)
			fileNames[i] = DATA_FILE + i;
		return fileNames;
	}

	/**
	 * Uploads data to server.
	 *
	 * @param data json array of Data
	 * @param name name of file where the data is saved (Function will clear the file afterwards)
	 * @param size size of data uploaded
	 */
	public static void upload(String data, final String name, final long size) {
		if (data.isEmpty()) return;
		if (!Extensions.isInitialized())
			Extensions.initialize((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE));

		String serialized = "{\"imei\":" + Extensions.getImei() +
				",\"device\":\"" + Build.MODEL +
				"\",\"manufacturer\":\"" + Build.MANUFACTURER +
				"\",\"api\":" + Build.VERSION.SDK_INT +
				",\"version\":" + BuildConfig.VERSION_CODE + ",";
		serialized += "\"data\":" + data + "}";

		RequestParams rp = new RequestParams();
		rp.add("imei", Extensions.getImei());
		rp.add("data", serialized);
		SyncHttpClient client = new SyncHttpClient();
		client.post(Network.URL_DATA_UPLOAD, rp, new AsyncHttpResponseHandler(Looper.getMainLooper()) {
			@Override
			public void onSuccess(int statusCode, Header[] headers, byte[] responseBody) {
				deleteFile(name);
				TrackerService.approxSize -= size;
				Log.d(TAG, "Successfully uploaded " + name);
			}

			@Override
			public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error) {
				Intent intent = new Intent(MainActivity.StatusReceiver.BROADCAST_TAG);
				intent.putExtra("cloudStatus", 1);
				LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
				requestUpload(context);
				FirebaseCrash.report(new Exception("Upload failed " + name + " code " + statusCode));
				//Log.w(TAG, "Upload failed " + name + " code " + statusCode);
			}
		});
	}

	/**
	 * Move file
	 *
	 * @param fileName    original file name
	 * @param newFileName new file name
	 * @return success
	 */
	public static boolean renameFile(String fileName, String newFileName) {
		String dir = context.getFilesDir().getPath();
		return new File(dir, fileName).renameTo(new File(dir, newFileName));
	}

	/**
	 * Delete file
	 *
	 * @param fileName file name
	 */
	private static void deleteFile(String fileName) {
		context.deleteFile(fileName);
	}

	/**
	 * Checks if file exists
	 *
	 * @param fileName file name
	 * @return existance of file
	 */
	public static boolean exists(String fileName) {
		return new File(context.getFilesDir().getAbsolutePath() + "/" + fileName).exists();
	}

	/**
	 * Handles any leftover files that could have been corrupted by some issue and reorders existing files
	 */
	public static void cleanup() {
		if (TrackerService.isActive) {
			Log.w(TAG, "cleanup cannot run when tracking is active");
			FirebaseCrash.report(new Throwable("cleanup cannot run when tracking is active"));
			return;
		}
		File[] files = context.getFilesDir().listFiles();

		Arrays.sort(files, (File a, File b) -> a.getName().compareTo(b.getName()));
		ArrayList<String> renamedFiles = new ArrayList<>();
		for (int i = 0; i < files.length; i++) {
			String name = files[i].getName();
			if (name.startsWith(DATA_FILE)) {
				renameFile(name, Integer.toString(i));
				renamedFiles.add(Integer.toString(i));
			}
		}

		for (String item : renamedFiles)
			renameFile(item, DATA_FILE + item);

		Setting.getPreferences().edit().putInt(KEY_FILE_ID, renamedFiles.size() == 0 ? 0 : renamedFiles.size() - 1).apply();
	}

	/**
	 * Inspects all data files and returns the total size
	 *
	 * @return total size of data
	 */
	public static long recountDataSize() {
		String[] fileNames = getDataFileNames(true);
		long size = 0;
		for (String fileName : fileNames)
			size += sizeOf(fileName);
		Setting.getPreferences().edit().putLong(KEY_SIZE, size).apply();
		return size;
	}

	/**
	 * Gets saved size of data.
	 *
	 * @return returns saved data size from shared preferences.
	 */
	public static long sizeOfData() {
		return Setting.getPreferences().getLong(KEY_SIZE, 0);
	}

	/**
	 * @param fileName Name of file
	 * @return Size of file
	 */
	private static long sizeOf(String fileName) {
		return new File(context.getFilesDir().getPath(), fileName).length();
	}


	/**
	 * Clears all data files
	 */
	public static void clearAllData() {
		SharedPreferences sp = Setting.getPreferences();
		int max = sp.getInt(KEY_FILE_ID, -1);
		for (int i = 0; i <= max; i++)
			context.deleteFile(DATA_FILE + i);

		sp.edit().remove(KEY_SIZE).remove(KEY_FILE_ID).apply();
	}

	/**
	 * Saves data to file. File is determined automatically.
	 *
	 * @param data json array to be saved, without [ at the beginning
	 * @return returns state value 2 - new file, 1 - error during saving, 0 - no new file, saved successfully
	 */
	public static int saveData(String data) {
		SharedPreferences sp = Setting.getPreferences();
		SharedPreferences.Editor edit = sp.edit();

		int id = sp.getInt(KEY_FILE_ID, 0);
		boolean newFile = false;

		if (sizeOf(DATA_FILE + id) > MAX_FILE_SIZE) {
			edit.putInt(KEY_FILE_ID, ++id);
			newFile = true;
		}

		String fileName = DATA_FILE + id;

		if (!saveStringAppend(fileName, data))
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
	public static void saveString(String fileName, String data) {
		try {
			FileOutputStream outputStream = MainActivity.context.openFileOutput(fileName, Context.MODE_PRIVATE);
			OutputStreamWriter osw = new OutputStreamWriter(outputStream);
			osw.write(data);
			osw.close();
		} catch (Exception e) {
			e.printStackTrace();
			FirebaseCrash.report(e);
		}
	}

	/**
	 * Appends string to file. If file does not exists, one is created. Should not be combined with other methods.
	 *
	 * @param fileName Name of file
	 * @param data     Json data to be saved
	 * @return Success
	 */
	private static boolean saveStringAppend(String fileName, String data) {
		StringBuilder sb = new StringBuilder(data);
		if (sb.charAt(0) == '[')
			sb.setCharAt(0, ',');
		else
			sb.insert(0, ',');

		data = sb.toString();
		FileOutputStream outputStream;
		try {
			outputStream = context.openFileOutput(fileName, Context.MODE_APPEND);
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
	 * Load string file as StringBuilder
	 *
	 * @param fileName file name
	 * @return content of file as StringBuilder
	 */
	public static StringBuilder loadStringAsBuilder(String fileName) {
		if (!exists(fileName)) {
			Log.w(TAG, "file " + fileName + " does not exist");
			return null;
		}

		try {
			FileInputStream fis = context.openFileInput(fileName);
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
		if (array == null || array.length == 0)
			return "";
		String out = "[";
		String data;
		for (Object anArray : array) {
			data = objectToJSON(anArray);
			if (!data.equals(""))
				out += data + ",";
		}

		if (out.length() == 1)
			return "";

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
				else if (typeName.equals("double"))
					data = Double.toString(field.getDouble(o));
				else if (typeName.equals("long"))
					data = Long.toString(field.getLong(o));
				else if (typeName.equals("float"))
					data = Float.toString(field.getFloat(o));
				else if (typeName.equals("String")) {
					String val = (String) field.get(o);
					if (val != null)
						data = "\"" + val.replace("\"", "\\\"") + "\"";
				} else if (typeName.equals("int")) {
					int value = field.getInt(o);
					if (value != 0)
						data = Integer.toString(value);
				} else if (typeName.equals("boolean"))
					data = Boolean.toString(field.getBoolean(o));
				else if (!field.getType().isPrimitive())
					data = objectToJSON(field.get(o));
				else
					Log.e("type", "Unknown type " + typeName + " - " + field.getName());

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
}
