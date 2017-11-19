package com.adsamcik.signalcollector.signin;

import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.BuildConfig;
import com.adsamcik.signalcollector.interfaces.INonNullValueCallback;
import com.adsamcik.signalcollector.interfaces.IValueCallback;
import com.adsamcik.signalcollector.utility.Assist;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import junit.framework.Assert;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class User {
	public final String id;
	public final String token;

	private long wirelessPoints;

	private NetworkInfo networkInfo = null;
	private NetworkPreferences networkPreferences = null;

	private List<INonNullValueCallback<User>> callbackList = null;

	public User(String id, String token) {
		this.id = id;
		this.token = token;
	}

	public NetworkInfo getNetworkInfo() {
		return networkInfo;
	}

	public NetworkPreferences getNetworkPreferences() {
		return networkPreferences;
	}

	public long getWirelessPoints() {
		return wirelessPoints;
	}

	public void addWirelessPoints(long value) {
		wirelessPoints += value;
	}

	public void deserializeServerData(String json) {
		Gson gson = new GsonBuilder().registerTypeAdapter(User.class, new ServerUserDeserializer(this)).create();
		gson.fromJson(json, User.class);
	}

	void setServerData(long wirelessPoints, @NonNull NetworkInfo networkInfo, @NonNull NetworkPreferences networkPreferences) {
		this.networkInfo = networkInfo;
		this.networkPreferences = networkPreferences;

		if(callbackList != null) {
			for (INonNullValueCallback<User> cb: callbackList)
				cb.callback(this);
			callbackList = null;
		}
	}

	void mockServerData() {
		if(!Assist.isEmulator() && !BuildConfig.DEBUG)
			throw new RuntimeException("Cannot mock server data on production version");
		wirelessPoints = Math.abs((System.currentTimeMillis() * System.currentTimeMillis()) % 64546);
		networkPreferences = new NetworkPreferences();
		networkPreferences.renewMap = true;
		networkPreferences.renewPersonalMap = false;

		networkInfo = new NetworkInfo();

		networkInfo.feedbackAccess = false;
		networkInfo.mapAccessUntil = System.currentTimeMillis();
		networkInfo.personalMapAccessUntil = 0;

	}

	public boolean isServerDataAvailable() {
		return networkInfo == null || networkPreferences == null;
	}

	public void addServerDataCallback(@NonNull INonNullValueCallback<User> callback) {
		if(isServerDataAvailable())
			callback.callback(this);
		else {
			if(callbackList == null)
				callbackList = new ArrayList<>();
			callbackList.add(callback);
		}
	}

	public class NetworkInfo {
		public long mapAccessUntil;
		public long personalMapAccessUntil;
		public boolean feedbackAccess;
		public boolean uploadAccess;


		public boolean hasMapAccess() {
			return System.currentTimeMillis() < mapAccessUntil;
		}

		public boolean hasPersonalMapAccess() {
			return System.currentTimeMillis() < personalMapAccessUntil;
		}
	}

	public class NetworkPreferences {
		public boolean renewMap;
		public boolean renewPersonalMap;
	}

	private final class ServerUserDeserializer implements JsonDeserializer<User> {
		private final User user;

		private ServerUserDeserializer(@NonNull User user) {
			this.user = user;
		}

		@Override
		public User deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
			JsonObject object = json.getAsJsonObject();
			long wirelessPoints = object.get("wirelessPoints").getAsLong();
			User.NetworkInfo networkInfo = context.deserialize(object.get("networkInfo"), User.NetworkInfo.class);
			User.NetworkPreferences networkPreferences = context.deserialize(object.get("networkPreferences"), User.NetworkPreferences.class);
			user.setServerData(wirelessPoints, networkInfo, networkPreferences);
			return user;
		}
	}

}
