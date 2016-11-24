package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IStateValueCallback;
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
	 * @param <S>                 State type (enum)
	 * @param <T>                 Value type
	 */
	public static <S, T> void load(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull Class<T> tClass, @NonNull final IStateValueCallback<Source, T> callback) {
		loadString(url, updateTimeInMinutes, context, preferenceString, (src, value) -> callback.callback(src, Assist.tryFromJson(value, tClass)));
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
	public static void loadString(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull final IStateValueCallback<Source, String> callback) {
		final long lastUpdate = Preferences.get(context).getLong(preferenceString, -1);
		if (System.currentTimeMillis() - lastUpdate > updateTimeInMinutes * Assist.MINUTE_IN_MILLISECONDS || lastUpdate == -1) {
			if (!Assist.hasNetwork()) {
				if (lastUpdate == -1)
					callback.callback(Source.no_data, null);
				else
					callback.callback(Source.cache_no_internet, DataStore.loadString(preferenceString));
				return;
			}

			OkHttpClient client = new OkHttpClient();
			Request request = new Request.Builder().url(url).build();

			client.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					if (lastUpdate != -1)
						callback.callback(Source.cache_connection_failed, DataStore.loadString(preferenceString));
					else
						callback.callback(Source.cache_connection_failed, null);
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					String json = response.body().string();
					response.close();

					Preferences.get(context).edit().putLong(preferenceString, System.currentTimeMillis()).apply();
					if (json.isEmpty()) {
						if (lastUpdate == -1)
							callback.callback(Source.no_data, null);
						else
							callback.callback(Source.cache_invalid_data, DataStore.loadString(preferenceString));
					} else {
						DataStore.saveString(preferenceString, json);
						callback.callback(Source.network, json);
					}
				}
			});
		} else
			callback.callback(Source.cache, DataStore.loadString(preferenceString));
	}

	public enum Source {
		cache,
		network,
		cache_no_internet,
		cache_connection_failed,
		cache_invalid_data,
		no_data;

		public boolean isSuccess() {
			return this.ordinal() <= 1;
		}

		public String toString(@NonNull Context context) {
			switch (this) {
				case cache_connection_failed:
					return context.getString(R.string.error_connection_failed);
				case cache_no_internet:
					return context.getString(R.string.error_no_internet);
				case cache_invalid_data:
					return context.getString(R.string.error_invalid_data);
				case no_data:
					return context.getString(R.string.error_no_data);
				default:
					return "";
			}
		}
	}
}
