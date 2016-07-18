package com.adsamcik.signalcollector;

public final class Network {
	public static final String URL_WEB = "http://collector.adsamcik.xyz/";
	public static final String URL_USER_REGISTRATION = URL_WEB + "register/user";
	public static final String URL_TOKEN_REGISTRATION = URL_WEB + "register/device";
	public static final String URL_DATA_UPLOAD = URL_WEB + "upload.php";
	//todo update path to tiles to new format zoom/x/y.png
	public static final String URL_TILES = URL_WEB + "tiles/z%dx%dy%dt%s.png";
	public static final String URL_USER_STATS = URL_WEB + "stats/userdata";
	public static final String URL_STATS = URL_WEB + "data/stats.json";

	public static int cloudStatus = 0;

	//todo encryption
	public static String encrypt(String data) {
		throw new RuntimeException("NYI");
	}

	//todo decryption
	public static String decrypt(String data) {
		throw new RuntimeException("NYI");
	}
}
