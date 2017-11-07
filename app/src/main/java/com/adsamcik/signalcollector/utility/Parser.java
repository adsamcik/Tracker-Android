package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.file.DataStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class Parser {

	/**
	 * Tries to parse json to object
	 *
	 * @param json   json
	 * @param tClass object class
	 * @param <T>    object type
	 * @return object if success, nul otherwise
	 */
	public static <T> T tryFromJson(String json, Class<T> tClass) {
		if (json != null && !json.isEmpty()) {
			try {
				return new Gson().fromJson(json, tClass);
			} catch (JsonSyntaxException e) {
				FirebaseCrash.report(e);
			}
		}
		return null;
	}


	public static ArrayList<String[]> parseTSVFromFile(@NonNull Context context, @NonNull String fileName) {
		if (DataStore.exists(context, fileName)) {
			ArrayList<String[]> items = new ArrayList<>();
			try (FileInputStream fis = context.openFileInput(fileName)) {
				InputStreamReader isr = new InputStreamReader(fis);
				BufferedReader br = new BufferedReader(isr);
				String receiveString;

				while ((receiveString = br.readLine()) != null)
					items.add(parseLine(receiveString));

				isr.close();
				return items;
			} catch (IOException e) {
				FirebaseCrash.report(e);
			}
		}

		return null;
	}

	private static String[] parseLine(String line) {
		if (line == null || line.length() == 0)
			return null;
		return line.split("\t");
	}


}
