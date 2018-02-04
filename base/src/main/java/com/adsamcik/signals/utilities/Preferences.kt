package com.adsamcik.signals.utilities

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.adsamcik.signals.utilities.R.style.AppThemeLight

object Preferences {
    private const val TAG = "SignalsSetting"

    const val PREF_THEME = "theme"
    val DEFAULT_THEME = AppThemeLight

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
}
