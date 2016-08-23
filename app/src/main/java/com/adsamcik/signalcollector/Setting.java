package com.adsamcik.signalcollector;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;

import com.adsamcik.signalcollector.data.StatDay;
import com.google.gson.Gson;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public class Setting {
	public static final int UPLOAD_JOB = 513;

	public static final String SCHEDULED_UPLOAD = "uploadSCHEDULED";
	public static final String SENT_TOKEN_TO_SERVER = "sentTokenToServer";
	public static final String REGISTERED_USER = "playUserRegistered";
	public static final String BACKGROUND_TRACKING = "backgroundTracking";
	public static final String AUTO_UPLOAD = "autoUpload";
	public static final String HAS_BEEN_LAUNCHED = "hasBeenLaunched";
	public static final String STOP_TILL_RECHARGE = "stoppedTillRecharge";

	//Local tracking stats
	//deprecated left to ensure removal from all test devices
	public static final String STATS_STAT_WEEK = "statsStatWeek";

	public static final String STATS_WIFI_FOUND = "statsWifiFound";
	public static final String STATS_CELL_FOUND = "statsCellFound";
	public static final String STATS_LOCATIONS_FOUND = "statsLocationsFound";
	public static final String STATS_MINUTES = "statsMinutes";
	public static final String STATS_STAT_LAST_DAY = "statsLastDay";
	public static final String STATS_LAST_7_DAYS = "statsLast7Days";

	public static final String STATS_VERSION = "statsVersion";

	public static final String TRACKING_WIFI_ENABLED = "trackingWifiEnabled";
	public static final String TRACKING_CELL_ENABLED = "trackingCellEnabled";
	public static final String TRACKING_PRESSURE_ENABLED = "trackingPressureEnabled";
	public static final String TRACKING_NOISE_ENABLED = "trackingNoiseEnabled";

	static final int MAX_DAY_DIFF_STATS = 7;

	private static SharedPreferences sharedPreferences;

	/**
	 * Initialize shared preferences. It's usually good to call it.
	 *
	 * @param c context
	 */
	public static void initializeSharedPreferences(@NonNull Context c) {
		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
	}

	/**
	 * Will stop tracking until phone is connected to charger
	 *
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
	 * @return Shared preferences
	 */
	public static SharedPreferences getPreferences(@NonNull Context c) {
		if (sharedPreferences == null)
			sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
		return sharedPreferences;
	}

	/**
	 * Get shared preferences
	 * This function will crash if shared preferences were never initialized
	 * Always prefer to send context if posssible.
	 *
	 * @return Shared preferences
	 */
	public static SharedPreferences getPreferences() {
		if (sharedPreferences == null)
			throw new RuntimeException("Shared preferences are null and no context was provided");
		return sharedPreferences;
	}

	public static void checkStatsDay(@NonNull Context context) {
		long now = Calendar.getInstance().getTime().getTime();
		int dayDiff = (int) (now - Setting.getPreferences(context).getLong(Setting.STATS_STAT_LAST_DAY, -1)) / Assist.DAY_IN_MILLISECONDS;
		if (dayDiff > 0) {
			long roundDay = dayDiff * Assist.DAY_IN_MILLISECONDS;
			SharedPreferences sp = getPreferences(context);
			Set<String> stringStats = sp.getStringSet(STATS_LAST_7_DAYS, null);
			Set<StatDay> stats = fromJson(stringStats, dayDiff);

			stats.add(new StatDay(sp.getInt(STATS_MINUTES, 0), sp.getInt(STATS_LOCATIONS_FOUND, 0), sp.getInt(STATS_WIFI_FOUND, 0), sp.getInt(STATS_CELL_FOUND, 0), dayDiff));

			if (stringStats == null)
				stringStats = new HashSet<>();
			else
				stringStats.clear();

			Gson gson = new Gson();
			for (StatDay day : stats)
				stringStats.add(gson.toJson(day));

			sp.edit().putLong(STATS_STAT_LAST_DAY, roundDay).putStringSet(STATS_LAST_7_DAYS, stringStats).apply();
		}
	}

	public static StatDay countStats(@NonNull Context context) {
		SharedPreferences sp = getPreferences(context);
		StatDay result = new StatDay(sp.getInt(STATS_MINUTES, 0), sp.getInt(STATS_LOCATIONS_FOUND, 0), sp.getInt(STATS_WIFI_FOUND, 0), sp.getInt(STATS_CELL_FOUND, 0), 0);
		Set<StatDay> set = fromJson(sp.getStringSet(STATS_LAST_7_DAYS, null), 0);
		for(StatDay stat : set)
			result.add(stat);
		return result;
	}

	private static Set<StatDay> fromJson(Set<String> set, int age) {
		Set<StatDay> statDays = new HashSet<>();
		if (set == null)
			return statDays;
		Gson gson = new Gson();
		for (String val : set) {
			StatDay sd = gson.fromJson(val, StatDay.class);
			if (age <= 0 || sd.age(age) < MAX_DAY_DIFF_STATS)
				statDays.add(sd);
		}
		return statDays;
	}

}
