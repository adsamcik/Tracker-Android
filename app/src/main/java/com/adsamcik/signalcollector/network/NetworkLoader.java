package com.adsamcik.signalcollector.network;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.interfaces.IStateValueCallback;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.CacheStore;
import com.adsamcik.signalcollector.utility.Parser;
import com.adsamcik.signalcollector.utility.Preferences;
import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
	 * @param <T>                 Value type
	 */
	public static <T> void request(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull Class<T> tClass, @NonNull final IStateValueCallback<Source, T> callback) {
		requestString(Network.client(null, context),
				new Request.Builder().url(url).build(),
				updateTimeInMinutes,
				context,
				preferenceString, (src, value) -> callback.callback(src, Parser.tryFromJson(value, tClass)));
	}

	/**
	 * Loads json from the web and converts it to java object
	 *
	 * @param url                 URL
	 * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
	 * @param context             Context
	 * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
	 * @param tClass              Class of the type
	 * @param callback            Callback which is called when the result is ready
	 * @param <T>                 Value type
	 */
	public static <T> void requestSigned(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull Class<T> tClass, @NonNull final IStateValueCallback<Source, T> callback) {
		Signin.getUserAsync(context, user -> {
			if (user != null)
				requestString(Network.client(user.token, context),
						new Request.Builder().url(url).build(),
						updateTimeInMinutes,
						context,
						preferenceString, (src, value) -> callback.callback(src, Parser.tryFromJson(value, tClass)));
			else
				callback.callback(Source.no_data_sign_in_failed, null);
		});
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
	public static void requestStringSigned(@NonNull final String url, int updateTimeInMinutes, @NonNull final Context context, @NonNull final String preferenceString, @NonNull final IStateValueCallback<Source, String> callback) {
		Signin.getUserAsync(context, user -> {
			if (user != null)
				requestString(Network.client(user.token, context), new Request.Builder().url(url).build(), updateTimeInMinutes, context, preferenceString, callback);
			else
				callback.callback(Source.no_data_sign_in_failed, null);
		});
	}

	/**
	 * Method to requestPOST string from server.
	 *
	 * @param request             requestPOST data
	 * @param updateTimeInMinutes Update time in minutes (if last update was in less minutes, file will be loaded from cache)
	 * @param context             Context
	 * @param preferenceString    Name of the lastUpdate in sharedPreferences, also is used as file name + '.json'
	 * @param callback            Callback which is called when the result is ready
	 */
	public static void requestString(@NonNull OkHttpClient client, @NonNull final Request request, int updateTimeInMinutes, @NonNull final Context ctx, @NonNull final String preferenceString, @NonNull final IStateValueCallback<Source, String> callback) {
		final Context context = ctx.getApplicationContext();
		final long lastUpdate = Preferences.get(context).getLong(preferenceString, -1);
		if (System.currentTimeMillis() - lastUpdate > updateTimeInMinutes * Assist.MINUTE_IN_MILLISECONDS || lastUpdate == -1 || !CacheStore.exists(context, preferenceString)) {
			if (!Assist.hasNetwork(context)) {
				if (lastUpdate == -1)
					callback.callback(Source.no_data, null);
				else
					callback.callback(Source.cache_no_internet, CacheStore.loadString(context, preferenceString));
				return;
			}

			client.newCall(request).enqueue(new Callback() {
				@Override
				public void onFailure(@NonNull Call call, @NonNull IOException e) {
					if (lastUpdate != -1)
						callback.callback(Source.cache_connection_failed, CacheStore.loadString(context, preferenceString));
					else
						callback.callback(Source.no_data, null);

					FirebaseCrash.log("Load " + preferenceString);
					FirebaseCrash.report(e);
				}

				@Override
				public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
					ResponseBody body = response.body();
					if (body == null)
						return;

					String json = body.string();

					if (json.isEmpty()) {
						if (lastUpdate == -1)
							callback.callback(Source.no_data, null);
						else
							callback.callback(Source.cache_invalid_data, CacheStore.loadString(context, preferenceString));
					} else {
						Preferences.get(context).edit().putLong(preferenceString, System.currentTimeMillis()).apply();
						CacheStore.saveString(context, preferenceString, json);
						callback.callback(Source.network, json);
					}
				}
			});
		} else
			callback.callback(Source.cache, CacheStore.loadString(context, preferenceString));
	}

	public enum Source {
		cache,
		network,
		cache_no_internet,
		cache_connection_failed,
		cache_invalid_data,
		no_data,
		no_data_sign_in_failed;

		public boolean isSuccess() {
			return this.ordinal() <= 1;
		}

		public boolean isDataAvailable() {
			return this.ordinal() <= 4;
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
				case no_data_sign_in_failed:
					return context.getString(R.string.error_failed_signin);
				default:
					return "---";
			}
		}
	}
}
