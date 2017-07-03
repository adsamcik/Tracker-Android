package com.adsamcik.signalcollector.network;

public class Server {
	private static final String URL_WEB = "https://signals.adsamcik.com/";
	static final String URL_TOKEN_REGISTRATION = URL_WEB + "auth/register";
	static final String URL_DATA_UPLOAD = URL_WEB + "upload";
	static final String URL_TILES = URL_WEB + "map/%d/%d/%d/%s";
	static final String URL_USER_STATS = URL_WEB + "stats/user";
	static final String URL_STATS = URL_WEB + "data/stats.json";
	static final String URL_MAPS_AVAILABLE = URL_WEB + "map/available";
	static final String URL_FEEDBACK = URL_WEB + "feedback/new";
	static final String URL_USER_SETTINGS = URL_WEB + "user/settings";
	public static final String URL_USER_PRICES = URL_WEB + "user/prices";

	public static String generateVerificationString(String userID, Long length) {
		StringBuilder result = new StringBuilder(userID);
		int value = 0;
		int inserts = 0;
		for (int i = 0; i < userID.length(); i++) {
			value += (length / (int) userID.charAt(i)) % 10;
			char c = (char) (48 + value);
			result.setCharAt(i + inserts, c);
			if (value % 7 == 0)
				result.insert(i + inserts++, 48 + ((value + 3) % 10));
		}
		return result.toString();
	}
}
