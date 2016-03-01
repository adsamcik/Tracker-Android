package com.adsamcik.signalcollector;

import android.content.SharedPreferences;

import java.io.Serializable;

public class Setting implements Serializable {
	public static final String SENT_TOKEN_TO_SERVER = "sentTokenToServer";
	public static final String REGISTERED_USER = "playUserRegistered";
	//0-no tracking; 1-onFoot tracking; 2-onFoot and vehicle tracking
	public static final String BACKGROUND_TRACKING = "backgroundTracking";
	//0-no auto upload;1-wifi autoUpload;2-autoUpload
	public static final String AUTO_UPLOAD = "autoUpload";
	public static final String HAS_BEEN_LAUNCHED = "hasBeenLaunched";
	public static SharedPreferences sharedPreferences;

	public static void Initialize(SharedPreferences sp) {
		sharedPreferences = sp;
	}

}
