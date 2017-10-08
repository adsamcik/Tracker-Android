package com.adsamcik.signalcollector.utility;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.adsamcik.signalcollector.R;
import com.adsamcik.signalcollector.data.StatDay;
import com.adsamcik.signalcollector.services.TrackerService;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS;

public class Preferences {
	private static final String TAG = "SignalsSetting";

	public static final String PREF_THEME = "theme";
	public static final int DEFAULT_THEME = R.style.AppThemeLight;

	public static final String LAST_VERSION = "lastVersion";

	public static final String PREF_SCHEDULED_UPLOAD = "uploadSCHEDULED";
	public static final String PREF_SENT_TOKEN_TO_SERVER = "sentTokenToServer";
	public static final String PREF_REGISTERED_USER = "playUserRegistered";

	public static final String PREF_AUTO_TRACKING = "backgroundTracking";
	public static final int DEFAULT_AUTO_TRACKING = 1;
	public static final String PREF_AUTO_UPLOAD = "autoUpload";
	public static final int DEFAULT_AUTO_UPLOAD = 1;

	public static final String PREF_HAS_BEEN_LAUNCHED = "hasBeenLaunched";
	public static final String PREF_STOP_TILL_RECHARGE = "stoppedTillRecharge";

	public static final String PREF_USER_ID = "userID";

	public static final String PREF_AUTO_UPLOAD_AT_MB = "autoUpAtMB";
	public static final int DEFAULT_AUTO_UPLOAD_AT_MB = 3;

	public static final String PREF_AUTO_UPLOAD_SMART = "smartAutoUpload";
	public static final boolean DEFAULT_AUTO_UPLOAD_SMART = true;

	//Local tracking stats
	public static final String PREF_STATS_WIFI_FOUND = "statsWifiFound";
	public static final String PREF_STATS_CELL_FOUND = "statsCellFound";
	public static final String PREF_STATS_LOCATIONS_FOUND = "statsLocationsFound";
	public static final String PREF_STATS_MINUTES = "statsMinutes";
	public static final String PREF_STATS_STAT_LAST_DAY = "statsLastDay";
	public static final String PREF_STATS_LAST_7_DAYS = "statsLast7Days";
	public static final String PREF_STATS_UPLOADED = "statsUploaded";

	public static final String PREF_OLDEST_RECENT_UPLOAD = "oldestRecentUpload";

	public static final String PREF_GENERAL_STATS = "generalStats";
	public static final String PREF_STATS = "stats";
	public static final String PREF_USER_STATS = "userStats";
	public static final String PREF_AVAILABLE_MAPS = "availableMaps";

	public static final String PREF_USER_DATA = "userDATA";
	public static final String PREF_USER_PRICES = "userPRICES";

	public static final String PREF_TRACKING_WIFI_ENABLED = "trackingWifiEnabled";
	public static final boolean DEFAULT_TRACKING_WIFI_ENABLED = true;
	public static final String PREF_TRACKING_CELL_ENABLED = "trackingCellEnabled";
	public static final boolean DEFAULT_TRACKING_CELL_ENABLED = true;
	public static final String PREF_TRACKING_NOISE_ENABLED = "trackingNoiseEnabled";
	public static final boolean DEFAULT_TRACKING_NOISE_ENABLED = false;
	public static final String PREF_TRACKING_LOCATION_ENABLED = "trackingLocationEnabled";
	public static final boolean DEFAULT_TRACKING_LOCATION_ENABLED = true;

	public static final String PREF_ACTIVITY_UPDATE_RATE = "activityWatcher";
	public static final int DEFAULT_ACTIVITY_UPDATE_RATE = 30;

	public static final String PREF_COLLECTIONS_SINCE_LAST_UPLOAD = "collectionsSinceLastUpload";
	public static final String PREF_COLLECTIONS_IN_STORAGE = "collectionsInStorage";

	public static final String PREF_DEFAULT_MAP_OVERLAY = "defaultMapOverlay";

	public static final String PREF_UPLOAD_NOTIFICATIONS_ENABLED = "uploadNotificationsEnabled";

	public static final String PREF_SHOW_DEV_SETTINGS = "showDevSettings";
	public static final String PREF_DEV_ACTIVITY_TRACKING_ENABLED = "activityDebugTracking";
	public static final String PREF_DEV_ACTIVITY_TRACKING_STARTED = "adevTrackingStartTime";

	public static final String PREF_ACTIVE_CHALLENGE_LIST = "activeChallengeList";

	private static final int MAX_DAY_DIFF_STATS = 7;

	private static SharedPreferences sharedPreferences;

	/**
	 * Will stop tracking until phone is connected to charger
	 *
	 * @param c context
	 */
	public static void stopTillRecharge(@NonNull Context c) {
		get(c).edit().putBoolean(PREF_STOP_TILL_RECHARGE, true).apply();
		if (TrackerService.isRunning())
			c.stopService(new Intent(c, TrackerService.class));
	}

	/**
	 * Get shared preferences
	 * This function should never crash. Initializes preferences if needed.
	 *
	 * @param c Non-null context
	 * @return Shared preferences
	 */
	public static SharedPreferences get(@NonNull Context c) {
		if (sharedPreferences == null)
			sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
		return sharedPreferences;
	}

	public static int getTheme(@NonNull Context context) {
		return get(context).getInt(PREF_THEME, DEFAULT_THEME);
	}

	public static void setTheme(@NonNull Context context, int theme) {
		get(context).edit().putInt(PREF_THEME, theme).apply();
	}

	/**
	 * Checks if current day should be archived and clears up old StatDays
	 * @param context context
	 */
	public static void checkStatsDay(@Nullable Context context) {
		if(sharedPreferences == null) {
			if(context == null)
				throw new RuntimeException("Shared preferences and context are null");
			else
				sharedPreferences = get(context);
		}
		long todayUTC = Assist.getDayInUTC();
		int dayDiff = (int) (todayUTC - sharedPreferences.getLong(Preferences.PREF_STATS_STAT_LAST_DAY, -1)) / DAY_IN_MILLISECONDS;
		if (dayDiff > 0) {
			Set<String> stringStats = sharedPreferences.getStringSet(PREF_STATS_LAST_7_DAYS, null);
			List<StatDay> stats = fromJson(stringStats, dayDiff);

			stats.add(getCurrent(sharedPreferences));

			if (stringStats == null)
				stringStats = new HashSet<>();
			else
				stringStats.clear();

			Gson gson = new Gson();
			for (StatDay day : stats)
				stringStats.add(gson.toJson(day));

			sharedPreferences.edit()
					.putLong(PREF_STATS_STAT_LAST_DAY, todayUTC)
					.putStringSet(PREF_STATS_LAST_7_DAYS, stringStats)
					.putInt(PREF_STATS_MINUTES, 0)
					.putInt(PREF_STATS_LOCATIONS_FOUND, 0)
					.putInt(PREF_STATS_WIFI_FOUND, 0)
					.putInt(PREF_STATS_CELL_FOUND, 0)
					.putLong(PREF_STATS_UPLOADED, 0)
					.apply();
		}
	}

	/**
	 * Counts all stats and combines them to a single StatDay object
	 * @param context context
	 * @return sum of all StatDays
	 */
	public static StatDay countStats(@NonNull Context context) {
		SharedPreferences sp = get(context);
		StatDay result = getCurrent(sp);
		List<StatDay> set = fromJson(sp.getStringSet(PREF_STATS_LAST_7_DAYS, null), 0);
		//noinspection Convert2streamapi
		for (StatDay stat : set)
			result.add(stat);
		return result;
	}

	/**
	 * Creates stat day with today values
	 * @param sp shared preferences
	 * @return Today StatDay
	 */
	private static StatDay getCurrent(SharedPreferences sp) {
		return new StatDay(sp.getInt(PREF_STATS_MINUTES, 0), sp.getInt(PREF_STATS_LOCATIONS_FOUND, 0), sp.getInt(PREF_STATS_WIFI_FOUND, 0), sp.getInt(PREF_STATS_CELL_FOUND, 0), 0, sp.getLong(PREF_STATS_UPLOADED, 0));
	}

	/**
	 * Method loads data to list and checks if they are not too old
	 * @param set string set
	 * @param age how much should stats age
	 * @return list with items that are not too old
	 */
	private static List<StatDay> fromJson(Set<String> set, int age) {
		List<StatDay> statDays = new ArrayList<>();
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
