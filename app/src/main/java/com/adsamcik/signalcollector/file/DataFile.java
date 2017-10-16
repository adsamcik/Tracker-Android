package com.adsamcik.signalcollector.file;

import android.os.Build;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.MalformedJsonException;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.data.RawData;
import com.adsamcik.signalcollector.utility.Constants;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.adsamcik.signalcollector.file.DataFile.FileType.CACHE;
import static com.adsamcik.signalcollector.file.DataFile.FileType.STANDARD;
import static com.adsamcik.signalcollector.file.DataStore.PREF_CACHE_FILE_INDEX;
import static com.adsamcik.signalcollector.file.DataStore.PREF_DATA_FILE_INDEX;

public class DataFile {
	public static final String SEPARATOR = " ";

	private File file;
	private final String fileNameTemplate;
	private final Gson gson = new Gson();
	private int collectionCount;
	private boolean writeable;

	private boolean empty;

	@FileType
	private int type;

	public DataFile(@NonNull File file, @Nullable String fileNameTemplate, @Nullable String userID, @FileType int type) {
		this.file = file;
		this.fileNameTemplate = fileNameTemplate;
		this.type = userID == null ? CACHE : type;
		if (!file.exists() || file.length() == 0) {
			if (this.type == STANDARD)
				FileStore.saveString(file, "{\"userID\":\"" + userID + "\"," +
						"\"model\":\"" + Build.MODEL +
						"\",\"manufacturer\":\"" + Build.MANUFACTURER +
						"\",\"api\":" + Build.VERSION.SDK_INT +
						",\"version\":" + BuildConfig.VERSION_CODE + "," +
						"\"data\":", false);
			empty = true;
			writeable = true;
			collectionCount = 0;
		} else {
			String ascii = null;
			try {
				ascii = FileStore.loadLastAscii(file, 2);
			} catch (FileNotFoundException e) {
				FirebaseCrash.report(e);
			}

			writeable = ascii == null || !ascii.equals("]}");
			empty = ascii == null || ascii.endsWith(":");
			collectionCount = getCollectionCount(file);
		}
	}

	/**
	 * Returns number of collection in given file
	 *
	 * @param file File
	 * @return Number of collections
	 */
	public static int getCollectionCount(@NonNull File file) {
		String fileName = file.getName();
		int indexOf = fileName.indexOf(SEPARATOR) + SEPARATOR.length();
		if (indexOf > 2)
			return Integer.parseInt(fileName.substring(indexOf));
		else
			return 0;
	}

	/**
	 * Returns file's template
	 * File's template is common part shared by all files of the same type
	 *
	 * @param file File
	 * @return File template
	 */
	private static String getTemplate(@NonNull File file) {
		String fileName = file.getName();
		int indexOf = fileName.indexOf(SEPARATOR);
		if (indexOf > 2)
			return fileName.substring(0, indexOf);
		else
			return fileName;
	}

	private synchronized void updateCollectionCount(int collectionCount) {
		this.collectionCount += collectionCount;
		File newFile;
		if (fileNameTemplate != null)
			newFile = new File(file.getParentFile(), fileNameTemplate + SEPARATOR + this.collectionCount);
		else
			newFile = new File(file.getParentFile(), getTemplate(file) + SEPARATOR + this.collectionCount);

		if (!file.renameTo(newFile))
			FirebaseCrash.report(new Throwable("Failed to rename file"));
		else
			file = newFile;
	}

	@IntDef({STANDARD, CACHE})
	@Retention(RetentionPolicy.SOURCE)
	public @interface FileType {
		int STANDARD = 0;
		int CACHE = 1;
	}

	/**
	 * Add json array data to file
	 *
	 * @param jsonArray       Json array
	 * @param collectionCount Number of collections (items in array)
	 * @return true if adding was success, false otherwise
	 */
	public synchronized boolean addData(@NonNull String jsonArray, int collectionCount) {
		if (jsonArray.charAt(0) != '[')
			throw new IllegalArgumentException("Given string is not json array!");
		if (saveData(jsonArray)) {
			updateCollectionCount(collectionCount);
			return true;
		} else
			return false;
	}

	/**
	 * Add RawData array to file
	 *
	 * @param data RawData array
	 * @return true if adding was success, false otherwise
	 */
	public synchronized boolean addData(@NonNull RawData[] data) {
		if (!writeable) {
			try {
				new FileOutputStream(file, true).getChannel().truncate(file.length() - 2).close();
			} catch (IOException e) {
				FirebaseCrash.report(e);
				return false;
			}
			writeable = true;
		}

		if (saveData(gson.toJson(data))) {
			updateCollectionCount(data.length);
			return true;
		} else
			return false;
	}

	private synchronized boolean saveData(@NonNull String jsonArray) {
		try {
			boolean status = FileStore.saveAppendableJsonArray(file, jsonArray, true, empty);
			if (status)
				empty = false;
			return status;
		} catch (MalformedJsonException e) {
			//Should never happen, but w/e
			FirebaseCrash.report(e);
			return false;
		}
	}

	/**
	 * Closes DataFile
	 * File will be automatically reopened when saveData is called
	 *
	 * @return True if close was successful
	 */
	public synchronized boolean close() {
		try {
			String last2 = FileStore.loadLastAscii(file, 2);
			assert last2 != null;
			writeable = false;
			return last2.equals("]}") || FileStore.saveString(file, "]}", true);
		} catch (FileNotFoundException e) {
			FirebaseCrash.report(e);
			writeable = true;
			return false;
		}
	}

	/**
	 * Returns size of DataFile
	 *
	 * @return Size
	 */
	public synchronized long size() {
		return file.length();
	}

	/**
	 * Returns whether the DataFile is writeable
	 *
	 * @return True if is writeable
	 */
	public boolean isWriteable() {
		return writeable;
	}

	/**
	 * Returns FileType
	 *
	 * @return FileType
	 */
	public @FileType
	int getType() {
		return type;
	}

	/**
	 * Returns preference string for index
	 *
	 * @return Preference string for index
	 */
	public String getPreference() {
		switch (type) {
			case CACHE:
				return PREF_CACHE_FILE_INDEX;
			case STANDARD:
				return PREF_DATA_FILE_INDEX;
			default:
				return null;
		}
	}

	/**
	 * Checks if DataFile is larger or equal than maximum DataFile size
	 *
	 * @return True if is larger or equal than maximum DataFile size
	 */
	public synchronized boolean isFull() {
		return size() > Constants.MAX_DATA_FILE_SIZE;
	}
}
