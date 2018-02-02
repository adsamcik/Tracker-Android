package com.adsamcik.utilities

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.adsamcik.signalcollector.data.StatDay
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.google.gson.Gson
import java.util.*

object Preferences {
    private const val TAG = "SignalsSetting"

    const val PREF_THEME = "theme"
    const val DEFAULT_THEME = R.style.AppThemeLight

    const val LAST_VERSION = "lastVersion"

    const val PREF_SCHEDULED_UPLOAD = "uploadSCHEDULED"
    const val PREF_SENT_TOKEN_TO_SERVER = "sentTokenToServer"
    const val PREF_REGISTERED_USER = "playUserRegistered"

    const val PREF_AUTO_TRACKING = "backgroundTracking"
    const val DEFAULT_AUTO_TRACKING = 1
    const val PREF_AUTO_UPLOAD = "autoUpload"
    const val DEFAULT_AUTO_UPLOAD = 1

    const val PREF_HAS_BEEN_LAUNCHED = "hasBeenLaunched"
    const val PREF_STOP_TILL_RECHARGE = "stoppedTillRecharge"

    const val PREF_USER_ID = "userID"

    const val PREF_AUTO_UPLOAD_AT_MB = "autoUpAtMB"
    const val DEFAULT_AUTO_UPLOAD_AT_MB = 3

    const val PREF_AUTO_UPLOAD_SMART = "smartAutoUpload"
    const val DEFAULT_AUTO_UPLOAD_SMART = true

    //Local tracking stats
    const val PREF_STATS_WIFI_FOUND = "statsWifiFound"
    const val PREF_STATS_CELL_FOUND = "statsCellFound"
    const val PREF_STATS_LOCATIONS_FOUND = "statsLocationsFound"
    const val PREF_STATS_MINUTES = "statsMinutes"
    const val PREF_STATS_STAT_LAST_DAY = "statsLastDay"
    const val PREF_STATS_LAST_7_DAYS = "statsLast7Days"
    const val PREF_STATS_UPLOADED = "statsUploaded"

    const val PREF_OLDEST_RECENT_UPLOAD = "oldestRecentUpload"

    const val PREF_GENERAL_STATS = "generalStats"
    const val PREF_STATS = "stats"
    const val PREF_USER_STATS = "userStats"
    const val PREF_AVAILABLE_MAPS = "availableMaps"

    const val PREF_USER_DATA = "userDATA"
    const val PREF_USER_PRICES = "userPRICES"

    const val PREF_TRACKING_WIFI_ENABLED = "trackingWifiEnabled"
    const val DEFAULT_TRACKING_WIFI_ENABLED = true
    const val PREF_TRACKING_CELL_ENABLED = "trackingCellEnabled"
    const val DEFAULT_TRACKING_CELL_ENABLED = true
    const val PREF_TRACKING_NOISE_ENABLED = "trackingNoiseEnabled"
    const val DEFAULT_TRACKING_NOISE_ENABLED = false
    const val PREF_TRACKING_LOCATION_ENABLED = "trackingLocationEnabled"
    const val DEFAULT_TRACKING_LOCATION_ENABLED = true

    const val PREF_ACTIVITY_WATCHER_ENABLED = "activityWatcherEnabled"
    const val DEFAULT_ACTIVITY_WATCHER_ENABLED = false
    const val PREF_ACTIVITY_UPDATE_RATE = "activityUpdateRate"
    const val DEFAULT_ACTIVITY_UPDATE_RATE = 30

    const val PREF_COLLECTIONS_SINCE_LAST_UPLOAD = "collectionsSinceLastUpload"
    const val PREF_COLLECTIONS_IN_STORAGE = "collectionsInStorage"

    const val PREF_DEFAULT_MAP_OVERLAY = "defaultMapOverlay"

    const val PREF_UPLOAD_NOTIFICATIONS_ENABLED = "uploadNotificationsEnabled"

    const val PREF_SHOW_DEV_SETTINGS = "showDevSettings"
    const val PREF_DEV_ACTIVITY_TRACKING_ENABLED = "activityDebugTracking"
    const val PREF_DEV_ACTIVITY_TRACKING_STARTED = "adevTrackingStartTime"

    const val PREF_ACTIVE_CHALLENGE_LIST = "activeChallengeList"

    private const val MAX_DAY_DIFF_STATS = 7

    private var sharedPreferences: SharedPreferences? = null

    /**
     * Get shared preferences
     * This function should never crash. Initializes preferences if needed.
     *
     * @param c Non-null context
     * @return Shared preferences
     */
    fun getPref(c: Context): SharedPreferences {
        if (sharedPreferences == null)
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(c.applicationContext)
        return sharedPreferences!!
    }

    fun getTheme(context: Context): Int = getPref(context).getInt(PREF_THEME, DEFAULT_THEME)

    fun setTheme(activity: Activity, theme: Int) {
        getPref(activity).edit().putInt(PREF_THEME, theme).apply()
        activity.setTheme(theme)
        //This ensures that all components use the proper theme
        activity.applicationContext.setTheme(theme)
    }

    fun setTheme(activity: Activity) {
        val theme = getTheme(activity)
        activity.setTheme(theme)
        //This ensures that all components use the proper theme
        activity.applicationContext.setTheme(theme)
    }

    /**
     * Checks if current day should be archived and clears up old StatDays
     * @param context context
     */
    fun checkStatsDay(context: Context) {
        val preferences = getPref(context)

        val todayUTC = Assist.todayUTC
        val dayDiff = (todayUTC - preferences.getLong(PREF_STATS_STAT_LAST_DAY, -1)).toInt() / DAY_IN_MILLISECONDS
        if (dayDiff > 0) {
            var stringStats: MutableSet<String>? = preferences.getStringSet(PREF_STATS_LAST_7_DAYS, null)
            val stats = fromJson(stringStats, dayDiff)

            stats.add(getCurrent(preferences))

            if (stringStats == null)
                stringStats = HashSet()
            else
                stringStats.clear()

            val gson = Gson()
            stats.mapTo(stringStats) { gson.toJson(it) }

            preferences.edit()
                    .putLong(PREF_STATS_STAT_LAST_DAY, todayUTC)
                    .putStringSet(PREF_STATS_LAST_7_DAYS, stringStats)
                    .putInt(PREF_STATS_MINUTES, 0)
                    .putInt(PREF_STATS_LOCATIONS_FOUND, 0)
                    .putInt(PREF_STATS_WIFI_FOUND, 0)
                    .putInt(PREF_STATS_CELL_FOUND, 0)
                    .putLong(PREF_STATS_UPLOADED, 0)
                    .apply()
        }
    }

    /**
     * Counts all stats and combines them to a single StatDay object
     * @param context context
     * @return sum of all StatDays
     */
    fun countStats(context: Context): StatDay {
        val sp = getPref(context)
        val result = getCurrent(sp)
        val set = fromJson(sp.getStringSet(PREF_STATS_LAST_7_DAYS, null), 0)

        result += set
        return result
    }

    /**
     * Creates stat day with today values
     * @param sp shared preferences
     * @return Today StatDay
     */
    private fun getCurrent(sp: SharedPreferences): StatDay =
            StatDay(sp.getInt(PREF_STATS_MINUTES, 0), sp.getInt(PREF_STATS_LOCATIONS_FOUND, 0), sp.getInt(PREF_STATS_WIFI_FOUND, 0), sp.getInt(PREF_STATS_CELL_FOUND, 0), 0, sp.getLong(PREF_STATS_UPLOADED, 0))

    /**
     * Method loads data to list and checks if they are not too old
     * @param set string set
     * @param age how much should stats age
     * @return list with items that are not too old
     */
    private fun fromJson(set: Set<String>?, age: Int): MutableList<StatDay> {
        val statDays = ArrayList<StatDay>()
        if (set == null)
            return statDays
        val gson = Gson()
        set
                .map { gson.fromJson(it, StatDay::class.java) }
                .filterTo(statDays) { age <= 0 || it.age(age) < MAX_DAY_DIFF_STATS }
        return statDays
    }
}
