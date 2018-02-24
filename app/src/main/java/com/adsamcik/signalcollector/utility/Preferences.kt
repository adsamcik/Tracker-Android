package com.adsamcik.signalcollector.utility

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.support.annotation.ColorInt
import android.support.annotation.ColorRes
import android.support.annotation.StringRes
import android.support.v4.content.ContextCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.data.StatDay
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.google.gson.Gson
import java.util.*

object Preferences {
    private val TAG = "SignalsSetting"

    val PREF_THEME = "theme"
    val DEFAULT_THEME = R.style.AppThemeLight

    val LAST_VERSION = "lastVersion"

    val PREF_SCHEDULED_UPLOAD = "uploadSCHEDULED"
    val PREF_SENT_TOKEN_TO_SERVER = "sentTokenToServer"
    val PREF_REGISTERED_USER = "playUserRegistered"

    val PREF_AUTO_TRACKING = "backgroundTracking"
    val DEFAULT_AUTO_TRACKING = 1
    val PREF_AUTO_UPLOAD = "autoUpload"
    val DEFAULT_AUTO_UPLOAD = 1

    val PREF_HAS_BEEN_LAUNCHED = "hasBeenLaunched"
    val PREF_STOP_TILL_RECHARGE = "stoppedTillRecharge"

    val PREF_USER_ID = "userID"

    val PREF_AUTO_UPLOAD_AT_MB = "autoUpAtMB"
    val DEFAULT_AUTO_UPLOAD_AT_MB = 3

    val PREF_AUTO_UPLOAD_SMART = "smartAutoUpload"
    val DEFAULT_AUTO_UPLOAD_SMART = true

    //Local tracking stats
    val PREF_STATS_WIFI_FOUND = "statsWifiFound"
    val PREF_STATS_CELL_FOUND = "statsCellFound"
    val PREF_STATS_LOCATIONS_FOUND = "statsLocationsFound"
    val PREF_STATS_MINUTES = "statsMinutes"
    val PREF_STATS_STAT_LAST_DAY = "statsLastDay"
    val PREF_STATS_LAST_7_DAYS = "statsLast7Days"
    val PREF_STATS_UPLOADED = "statsUploaded"

    val PREF_OLDEST_RECENT_UPLOAD = "oldestRecentUpload"

    val PREF_GENERAL_STATS = "generalStats"
    val PREF_STATS = "stats"
    val PREF_USER_STATS = "userStats"
    val PREF_AVAILABLE_MAPS = "availableMaps"

    val PREF_USER_DATA = "userDATA"
    val PREF_USER_PRICES = "userPRICES"

    val PREF_TRACKING_WIFI_ENABLED = "trackingWifiEnabled"
    val DEFAULT_TRACKING_WIFI_ENABLED = true
    val PREF_TRACKING_CELL_ENABLED = "trackingCellEnabled"
    val DEFAULT_TRACKING_CELL_ENABLED = true
    val PREF_TRACKING_NOISE_ENABLED = "trackingNoiseEnabled"
    val DEFAULT_TRACKING_NOISE_ENABLED = false
    val PREF_TRACKING_LOCATION_ENABLED = "trackingLocationEnabled"
    val DEFAULT_TRACKING_LOCATION_ENABLED = true

    val PREF_ACTIVITY_WATCHER_ENABLED = "activityWatcherEnabled"
    val DEFAULT_ACTIVITY_WATCHER_ENABLED = false
    val PREF_ACTIVITY_UPDATE_RATE = "activityUpdateRate"
    val DEFAULT_ACTIVITY_UPDATE_RATE = 30

    val PREF_COLLECTIONS_SINCE_LAST_UPLOAD = "collectionsSinceLastUpload"
    val PREF_COLLECTIONS_IN_STORAGE = "collectionsInStorage"

    val PREF_DEFAULT_MAP_OVERLAY = "defaultMapOverlay"

    val PREF_UPLOAD_NOTIFICATIONS_ENABLED = "uploadNotificationsEnabled"

    val PREF_SHOW_DEV_SETTINGS = "showDevSettings"
    val PREF_DEV_ACTIVITY_TRACKING_ENABLED = "activityDebugTracking"
    val PREF_DEV_ACTIVITY_TRACKING_STARTED = "adevTrackingStartTime"

    val PREF_ACTIVE_CHALLENGE_LIST = "activeChallengeList"

    private val MAX_DAY_DIFF_STATS = 7

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

        val todayUTC = Assist.dayInUTC
        val dayDiff = (todayUTC - preferences.getLong(Preferences.PREF_STATS_STAT_LAST_DAY, -1)).toInt() / DAY_IN_MILLISECONDS
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

fun SharedPreferences.getInt(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): Int {
    val resources = context.resources
    return getInt(resources.getString(key), resources.getString(defaultResource).toInt())
}

fun SharedPreferences.getString(context: Context, @StringRes key: Int, @StringRes defaultResource: Int): String {
    val resources = context.resources
    return getString(resources.getString(key), resources.getString(defaultResource))
}

@ColorInt
fun SharedPreferences.getColorResource(context: Context, @StringRes key: Int, @ColorRes defaultResource: Int): Int {
    val defaultColor = ContextCompat.getColor(context, defaultResource)
    return getInt(context.getString(key), defaultColor)
}
