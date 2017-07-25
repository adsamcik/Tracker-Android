package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.firebase.crash.FirebaseCrash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class CacheStore {
	private static final String TAG = "SignalsCacheStore";

	/**
	 * Saves string to file
	 *
	 * @param fileName file name
	 * @param data     string data
	 */
	@SuppressWarnings("SameParameterValue")
	public static boolean saveString(@NonNull Context context, String fileName, String data) {
		File file = new File(context.getCacheDir(), fileName);
		try {
			FileOutputStream outputStream = new FileOutputStream(file);
			OutputStreamWriter osw = new OutputStreamWriter(outputStream);
			osw.write(data);
			osw.close();
		} catch (Exception e) {
			FirebaseCrash.report(e);
			e.printStackTrace();
			return false;
		}
		return true;
	}


	/**
	 * Load string file as StringBuilder
	 *
	 * @param fileName file name
	 * @return content of file as StringBuilder
	 */
	public static StringBuilder loadStringAsBuilder(@NonNull Context context, @NonNull String fileName) {
		File file = new File(context.getCacheDir(), fileName);
		if (!file.exists())
			return null;

		try {
			FileInputStream fis = new FileInputStream(file);
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
	public static String loadString(@NonNull Context context, @NonNull String fileName) {
		StringBuilder sb = loadStringAsBuilder(context, fileName);
		if (sb != null)
			return sb.toString();
		else
			return null;
	}

	/**
	 * Checks if file exists
	 *
	 * @param fileName file name
	 * @return existence of file
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean exists(@NonNull Context context, @NonNull String fileName) {
		return new File(context.getCacheDir(), fileName).exists();
	}
}
