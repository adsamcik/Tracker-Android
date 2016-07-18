package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import java.io.Serializable;

public class Setting {
	public static final int UPLOAD_JOB = 513;

	public static final String SCHEDULED_UPLOAD = "uploadSCHEDULED";
	public static final String SENT_TOKEN_TO_SERVER = "sentTokenToServer";
	public static final String REGISTERED_USER = "playUserRegistered";
	public static final String BACKGROUND_TRACKING = "backgroundTracking";
	public static final String AUTO_UPLOAD = "autoUpload";
	public static final String HAS_BEEN_LAUNCHED = "hasBeenLaunched";
	public static final String STOP_TILL_RECHARGE = "stoppedTillRecharge";
	public static final String STATS_VERSION = "statsVersion";

	//Local tracking stats
	public static final String TRACKING_STAT_DAY = "trackingStatDay";
	public static final String TRACKING_WIFI_FOUND = "trackingWifiFound";
	public static final String TRACKING_CELL_FOUND = "trackingCellFound";
	public static final String TRACKING_LOCATIONS_FOUND = "trackingLocationsFound";

	public static final String BROADCAST_UPDATE_INFO = "SignalsUpdate";

	private static SharedPreferences sharedPreferences;

	/**
	 * Initialize shared preferences. It's usually good to call it.
	 * @param c context
	 */
	public static void initializeSharedPreferences(@NonNull Context c) {
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c);
	}

	/**
	 * Will stop tracking until phone is connected to charger
	 * @param c context
	 */
	public static void stopTillRecharge(@NonNull Context c) {
		getPreferences(c).edit().putBoolean(STOP_TILL_RECHARGE, true).apply();
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
