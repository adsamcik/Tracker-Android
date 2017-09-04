package com.adsamcik.signalcollector.file;

import android.content.Context;
import android.support.annotation.NonNull;

import java.io.File;

public class CacheStore {

	public static File file(@NonNull Context context, @NonNull String fileName) {
		return FileStore.file(context.getCacheDir(), fileName);
	}

	/**
	 * Saves string to file
	 *
	 * @param fileName file name
	 * @param data     string data
	 */
	@SuppressWarnings("SameParameterValue")
	public static boolean saveString(@NonNull Context context, @NonNull String fileName, @NonNull String data, boolean append) {
		return FileStore.saveString(file(context, fileName), data, append);
	}


	/**
	 * Load string file as StringBuilder
	 *
	 * @param fileName file name
	 * @return content of file as StringBuilder
	 */
	public static StringBuilder loadStringAsBuilder(@NonNull Context context, @NonNull String fileName) {
		return FileStore.loadStringAsBuilder(file(context, fileName));
	}

	/**
	 * Converts loadStringAsBuilder to string and handles nulls
	 *
	 * @param fileName file name
	 * @return content of file (empty if file has no content or does not exists)
	 */
	public static String loadString(@NonNull Context context, @NonNull String fileName) {
		return FileStore.loadString(file(context, fileName));
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

	public static void clearAll(@NonNull Context context) {
		FileStore.clearFolder(context.getCacheDir());
	}
}
