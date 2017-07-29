package com.adsamcik.signalcollector.network;

public class User {
	public final String id;
	public final String token;
	public long wirelessPoints;

	public NetworkInfo networkInfo = null;
	public NetworkPreferences networkPreferences = null;

	public User(String id, String token) {
		this.id = id;
		this.token = token;
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
}
