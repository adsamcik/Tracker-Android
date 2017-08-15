package com.adsamcik.signalcollector.network;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.adsamcik.signalcollector.enums.CloudStatus;
import com.adsamcik.signalcollector.utility.Assist;
import com.adsamcik.signalcollector.utility.Preferences;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.util.Collections;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.CookieJar;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

public final class Network {
	private static final String TAG = "SignalsNetwork";
	public static final String URL_DATA_UPLOAD = Server.URL_DATA_UPLOAD;
	public static final String URL_TILES = Server.URL_TILES;
	public static final String URL_PERSONAL_TILES = Server.URL_PERSONAL_TILES;
	public static final String URL_USER_STATS = Server.URL_USER_STATS;
	public static final String URL_STATS = Server.URL_STATS;
	public static final String URL_GENERAL_STATS = Server.URL_GENERAL_STATS;
	public static final String URL_MAPS_AVAILABLE = Server.URL_MAPS_AVAILABLE;
	public static final String URL_FEEDBACK = Server.URL_FEEDBACK;
	public static final String URL_USER_INFO = Server.URL_USER_INFO;
	public static final String URL_USER_PRICES = Server.URL_USER_PRICES;
	public static final String URL_CHALLENGES_LIST = Server.URL_CHALLENGES_LIST;

	public static final String URL_USER_UPDATE_MAP_PREFERENCE = Server.URL_USER_UPDATE_MAP_PREFERENCE;
	public static final String URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE = Server.URL_USER_UPDATE_PERSONAL_MAP_PREFERENCE;

	public static CloudStatus cloudStatus;

	private static PersistentCookieJar cookieJar = null;

	private static ConnectionSpec getSpec() {
		return new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
				.tlsVersions(TlsVersion.TLS_1_2)
				.cipherSuites(CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
						CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
						CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
						CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA)
				.build();
	}

	private static CookieJar getCookieJar(@NonNull Context context) {
		return cookieJar != null ? cookieJar : (cookieJar = new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context)));
	}

	public static void clearCookieJar() {
		cookieJar.clear();
	}

	public static OkHttpClient client(@NonNull final Context context, @Nullable final String userToken) {
		return userToken == null ? client(context) : new OkHttpClient.Builder()
				.connectionSpecs(Collections.singletonList(getSpec()))
				.cookieJar(getCookieJar(context))
				.authenticator((route, response) -> {
					if (response.request().header("userToken") != null)
						return null;

					return response.request().newBuilder()
							.header("userToken", userToken)
							.build();
				})
				.build();
	}

	private static OkHttpClient client(@NonNull final Context context) {
		return new OkHttpClient.Builder()
				.connectionSpecs(Collections.singletonList(getSpec()))
				.cookieJar(getCookieJar(context))
				.build();
	}

	public static Request requestGET(@NonNull final String url) {
		return new Request.Builder().url(url).build();
	}

	public static Request requestPOST(@NonNull final String url, @NonNull final RequestBody body) {
		return new Request.Builder().url(url).post(body).build();
	}

	public static MultipartBody.Builder generateAuthBody(@NonNull final String userToken) {
		return new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("userToken", userToken)
				.addFormDataPart("manufacturer", Build.MANUFACTURER)
				.addFormDataPart("model", Build.MODEL);
	}

	public static void register(@NonNull final Context context, @NonNull final String userToken, @NonNull final String token) {
		if (!Assist.isEmulator())
			register(context, userToken, "token", token, Preferences.PREF_SENT_TOKEN_TO_SERVER, Server.URL_TOKEN_REGISTRATION);
	}

	private static void register(@NonNull Context context, @NonNull final String userToken, @NonNull final String valueName, @NonNull final String value, @NonNull final String preferencesName, @NonNull final String url) {
		RequestBody formBody = generateAuthBody(userToken)
				.addFormDataPart(valueName, value)
				.build();
		Request request = requestPOST(url, formBody);
		client(context, userToken).newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				FirebaseCrash.log("Register " + preferencesName);
				FirebaseCrash.report(e);
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				Preferences.get(context).edit().putBoolean(preferencesName, true).apply();
				response.close();
			}
		});
	}

	public static String generateVerificationString(String uid, Long length) {
		return Server.generateVerificationString(uid, length);
	}
}