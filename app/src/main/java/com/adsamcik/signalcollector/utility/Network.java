package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.enums.CloudStatus;
import com.franmontiel.persistentcookiejar.ClearableCookieJar;
import com.franmontiel.persistentcookiejar.PersistentCookieJar;
import com.franmontiel.persistentcookiejar.cache.SetCookieCache;
import com.franmontiel.persistentcookiejar.persistence.SharedPrefsCookiePersistor;
import com.google.firebase.crash.FirebaseCrash;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CipherSuite;
import okhttp3.ConnectionSpec;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.TlsVersion;

public final class Network {
	public static final String URL_DATA_UPLOAD = Server.URL_DATA_UPLOAD;
	public static final String URL_TILES = Server.URL_TILES;
	public static final String URL_USER_STATS = Server.URL_USER_STATS;
	public static final String URL_STATS = Server.URL_STATS;
	public static final String URL_MAPS_AVAILABLE = Server.URL_MAPS_AVAILABLE;
	public static final String URL_FEEDBACK = Server.URL_FEEDBACK;

	public static CloudStatus cloudStatus;

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
		return new PersistentCookieJar(new SetCookieCache(), new SharedPrefsCookiePersistor(context));
	}

	public static OkHttpClient client(final String userToken, final Context context) {
		return new OkHttpClient.Builder()
				.connectionSpecs(Collections.singletonList(getSpec()))
				.cookieJar(getCookieJar(context))
				.authenticator((route, response) ->
						response.request().newBuilder()
								.headers(new Headers.Builder().add("userToken", userToken).add("manufacturer", Build.MANUFACTURER).add("model", Build.MODEL).build())
								.build())
				.build();
	}

	public static OkHttpClient client(final Context context) {
		return new OkHttpClient.Builder()
				.connectionSpecs(Collections.singletonList(getSpec()))
				.cookieJar(getCookieJar(context))
				.build();
	}

	public static Request request(final String url, final RequestBody body) {
		return new Request.Builder().url(url).post(body).build();
	}

	public static MultipartBody.Builder generateAuthBody(@NonNull final String userToken) {
		return new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("userToken", userToken)
				.addFormDataPart("manufacturer", Build.MANUFACTURER)
				.addFormDataPart("model", Build.MODEL);
	}

	public static void register(final String userToken, final String token, @NonNull final Context context) {
		if (userToken != null && token != null && !Assist.isEmulator())
			register(userToken, "token", token, Preferences.PREF_SENT_TOKEN_TO_SERVER, Server.URL_TOKEN_REGISTRATION, context);
	}

	private static void register(@NonNull final String userToken, @NonNull final String valueName, @NonNull final String value, @NonNull final String preferencesName, @NonNull final String url, @NonNull Context context) {
		RequestBody formBody = generateAuthBody(userToken)
				.addFormDataPart(valueName, value)
				.build();
		Request request = request(url, formBody);
		client(userToken, context).newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				FirebaseCrash.log("Register " + preferencesName);
				FirebaseCrash.report(e);
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
				Preferences.get().edit().putBoolean(preferencesName, true).apply();
				response.close();
			}
		});
	}

	public static String generateVerificationString(String uid, Long length) {
		return Server.generateVerificationString(uid, length);
	}
}