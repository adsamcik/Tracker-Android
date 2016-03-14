package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import java.io.Serializable;

public class Setting implements Serializable {
	public static final String SENT_TOKEN_TO_SERVER = "sentTokenToServer";
	public static final String REGISTERED_USER = "playUserRegistered";
	//0-no tracking; 1-onFoot tracking; 2-onFoot and vehicle tracking
	public static final String BACKGROUND_TRACKING = "backgroundTracking";
	//0-no auto upload;1-wifi autoUpload;2-autoUpload
	public static final String AUTO_UPLOAD = "autoUpload";
	public static final String HAS_BEEN_LAUNCHED = "hasBeenLaunched";
	private static SharedPreferences sharedPreferences;

	public static boolean isStopped = false;

	public static void recharging() {
		isStopped = false;
	}

	public static void initializeSharedPreferences(@NonNull Context c) {
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c);
	}

	/**
	 * Get shared preferences
	 * This function should never crash. Initializes preferences if needed.
	 *
	 * @param c Non-null context
	 * @return  Shared preferences
	 */
	public static SharedPreferences getPreferences(@NonNull Context c) {
		if(sharedPreferences == null)
			sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c);
		return sharedPreferences;
	}

	/**
	 * Get shared preferences
	 * This function will crash if shared preferences were never initialized
	 * Always prefer to send context if posssible.
	 *
	 * @return  Shared preferences
	 */
	public static SharedPreferences getPreferences() {
		if(sharedPreferences == null)
			throw new RuntimeException("Shared preferences are null and no context was provided");
		return sharedPreferences;
	}

}
