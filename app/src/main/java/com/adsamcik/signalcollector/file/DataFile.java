package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.os.Build;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.MalformedJsonException;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.data.RawData;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class DataFile {
	public static final int STANDARD = 0;
	public static final int CACHE = 1;

	private final File file;
	private final Gson gson = new Gson();
	private boolean writeable;

	@FileType
	private int type;


	/*SharedPreferences sp = Preferences.get();
	SharedPreferences.Editor edit = sp.edit();

	long fileSize = sizeOf(context, DATA_FILE + id);

	File cacheFile = file(context, DATA_CACHE_FILE);
		if (cacheFile.exists()) {
		if (cacheFile.length() > 0) {
			if(fileSize > Constants.U_MEBIBYTE / 2) {
				if(cacheFile.length() + fileSize > Constants.MAX_DATA_FILE_SIZE * 1.05) {

				}
			}
		}

		//It should never happen that delete fails
		if(!cacheFile.delete()) {
			FirebaseCrash.report(new Throwable("FAILED TO DELETE CACHE FILE"));
			cacheFile.deleteOnExit();
		}
	}

	String jsonArray = new Gson().toJson(data);

	int id = sp.getInt(PREF_DATA_FILE_INDEX, 0);
	boolean fileHasNoData;
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
			return SaveStatus.SAVE_FAILED;
		}
	}

		try {
		if (!FileStore.saveAppendableJsonArray(file(context, DATA_FILE + id), jsonArray, true, fileHasNoData)) {
			if (fileSize > Constants.MAX_DATA_FILE_SIZE)
				edit.apply();
			return SaveStatus.SAVE_FAILED;
		}
	} catch (MalformedJsonException e) {
		if (fileSize > Constants.MAX_DATA_FILE_SIZE)
			edit.apply();
		FirebaseCrash.report(e);
		return SaveStatus.SAVE_FAILED;
	}

	int dataSize = data.getBytes(Charset.defaultCharset()).length;
	approxSize = sp.getLong(PREF_COLLECTED_DATA_SIZE, 0) + dataSize;
		edit.putLong(PREF_COLLECTED_DATA_SIZE, approxSize).apply();

		return fileHasNoData && id > 0 ? SaveStatus.SAVE_SUCCESS_FILE_DONE : SaveStatus.SAVE_SUCCESS;*/

	public DataFile(@NonNull File file, @Nullable String userID, @FileType int type) {
		this.file = file;
		this.type = userID == null ? CACHE : type;

		if (!file.exists() || file.length() == 0) {
			if (this.type == STANDARD)
				FileStore.saveString(file, "{\"userID\":\"" + userID + "\"," +
						"\"model\":\"" + Build.MODEL +
						"\",\"manufacturer\":\"" + Build.MANUFACTURER +
						"\",\"api\":" + Build.VERSION.SDK_INT +
						",\"version\":" + BuildConfig.VERSION_CODE + "," +
						"\"data\":", false);
			writeable = true;
		} else {
			String ascii = null;
			try {
				ascii = FileStore.loadLastAscii(file, 2);
			} catch (FileNotFoundException e) {
				FirebaseCrash.report(e);
			}

			writeable = ascii == null || ascii.equals("]}");
		}
	}

	@IntDef({STANDARD, CACHE})
	@Retention(RetentionPolicy.SOURCE)
	public @interface FileType {
	}

	public boolean addData(@NonNull RawData[] data) {
		String jsonArray = gson.toJson(data);
		try {
			return FileStore.saveAppendableJsonArray(file, jsonArray, true, file.length() > 0);
		} catch (MalformedJsonException e) {
			//Should never happen, but w/e
			FirebaseCrash.report(e);
			return false;
		}
	}

	public boolean close(@NonNull Context context) {
		try {
			String last2 = FileStore.loadLastAscii(file, 2);
			assert last2 != null;
			if (!last2.equals("]}"))
				FileStore.saveString(file, "]}", true);
			return true;
		} catch (FileNotFoundException e) {
			FirebaseCrash.report(e);
			return false;
		}
	}

	public long size() {
		return file.length();
	}

	public boolean isWriteable() {
		return writeable;
	}

	public @FileType
	int getType() {
		return type;
	}
}
