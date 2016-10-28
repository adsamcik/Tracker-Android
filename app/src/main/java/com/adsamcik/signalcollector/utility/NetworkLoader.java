package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetworkLoader {

	/**
	 * Loads json from the web and converts it to java object
	 *
	 * @param url                 URL
	 * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
	 * @param context             Context
	 * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
	 * @param tClass              Class of the type
	 * @param callback            Callback which is called when the result is ready
	 * @param <T>                 Type
	 */
	public static <T> void load(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull Class<T> tClass, @NonNull final IValueCallback<T> callback) {
		loadString(url, updateTimeInMinutes, context, preferenceString, value -> callback.callback(new Gson().fromJson(value, tClass)));
	}

	/**
	 * Loads json to string array using
	 *
	 * @param url                 URL
	 * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
	 * @param context             Context
	 * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
	 * @param callback            Callback which is called when the result is ready
	 */
	public static void loadStringArray(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull final IValueCallback<ArrayList<String>> callback) {
		loadString(url, updateTimeInMinutes, context, preferenceString, value -> callback.callback(Assist.jsonToStringArray(value)));
	}

	/**
	 * Method which loads string from the web or cache
	 *
	 * @param url                 URL
	 * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
	 * @param context             Context
	 * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
	 * @param callback            Callback which is called when the result is ready
	 */
	private static void loadString(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull final IValueCallback<String> callback) {
		final long lastUpdate = Preferences.get(context).getLong(preferenceString, -1);
		if (lastUpdate == -1 || System.currentTimeMillis() - lastUpdate > updateTimeInMinutes * Assist.MINUTE_IN_MILLISECONDS) {
			if (!Assist.hasNetwork() && lastUpdate != -1) {
				String json = DataStore.loadString(preferenceString);
				callback.callback(json);
				return;
			}

			OkHttpClient client = new OkHttpClient();
			Request request = new Request.Builder().url(url).build();

			client.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					if (lastUpdate != -1)
						callback.callback(DataStore.loadString(preferenceString));
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					Preferences.get(context).edit().putLong(preferenceString, System.currentTimeMillis()).apply();
					String json = response.body().string();
					response.close();
					if (json.isEmpty() && lastUpdate != -1)
						callback.callback(DataStore.loadString(preferenceString));
					else
						callback.callback(json);
				}
			});
		} else
			callback.callback(DataStore.loadString(preferenceString));
	}
}
