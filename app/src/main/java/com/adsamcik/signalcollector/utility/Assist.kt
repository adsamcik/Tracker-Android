package com.adsamcik.signalcollector.utility

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Point
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.v4.app.FragmentActivity
import android.support.v4.content.ContextCompat
import android.support.v4.content.res.ResourcesCompat
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.enums.NavBarPosition
import com.adsamcik.signalcollector.extensions.connectivityManager
import com.adsamcik.signalcollector.extensions.inputMethodManager
import com.adsamcik.signalcollector.extensions.locationManager
import com.adsamcik.signalcollector.extensions.windowManager
import com.adsamcik.signalcollector.fragments.FragmentPrivacyDialog
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.text.DecimalFormat
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

/**
 * All purpose utility singleton containing various utility functions
 */
object Assist {

    /**
     * Converts raw byte count to human readable byte count
     *
     * @param bytes bytes
     * @param si    if true uses decimal (1000) representation otherwise binary (1024)
     * @return human readable byte count
     */
    fun humanReadableByteCount(bytes: Long, si: Boolean): String {
        val unit = if (si) 1000 else 1024
        if (bytes < unit) return bytes.toString() + " B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
        val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
        return String.format(Locale.getDefault(), "%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), pre)
    }

    /**
     * Returns orientation of the device as one of the following constants
     * [Surface.ROTATION_0], [Surface.ROTATION_90], [Surface.ROTATION_180], [Surface.ROTATION_270].
     *
     * @return One of the following [Surface.ROTATION_0], [Surface.ROTATION_90], [Surface.ROTATION_180], [Surface.ROTATION_270]
     */
    fun orientation(context: Context): Int {
        return context.windowManager.defaultDisplay.rotation
    }

    /**
     * Calculates current navbar size and it's current position.
     * Size is stored inside Point class.
     *
     * @param context Context
     * @return (Position, Size)
     */
    fun navbarSize(context: Context): Pair<NavBarPosition, Point> {
        val display = context.windowManager.defaultDisplay

        val appUsableSize = Point()
        val realScreenSize = Point()

        display.getRealSize(realScreenSize)
        display.getSize(appUsableSize)
        val rotation = display.rotation

        // navigation bar on the right
        if (appUsableSize.x < realScreenSize.x) {
            //App supports only phones so there should be no scenario where orientation is 0 or 180
            return Pair(
                    if (rotation == Surface.ROTATION_90 || Build.VERSION.SDK_INT < 26) NavBarPosition.RIGHT
                    else NavBarPosition.LEFT,
                    Point(realScreenSize.x - appUsableSize.x, appUsableSize.y))
        }

        // navigation bar at the bottom
        return if (appUsableSize.y < realScreenSize.y) {
            Pair(NavBarPosition.BOTTOM, Point(appUsableSize.x, realScreenSize.y - appUsableSize.y))
        } else Pair(NavBarPosition.UNKNOWN, Point())
    }

    /**
     * Generates position between two passed positions based on time
     *
     * @param locationOne first location
     * @param locationTwo second location
     * @param time        Value between 0 and 1. 0 is locationOne, 1 is locationTwo
     * @return interpolated location
     */
    fun interpolateLocation(locationOne: Location, locationTwo: Location, time: Double): Location {
        val l = Location("interpolation")
        l.latitude = locationOne.latitude + (locationTwo.latitude - locationOne.latitude) * time
        l.longitude = locationOne.longitude + (locationTwo.longitude - locationOne.longitude) * time
        l.altitude = locationOne.altitude + (locationTwo.altitude - locationOne.altitude) * time
        return l
    }

    /**
     * Checks if required permission are available
     * ACCESS_FINE_LOCATION - GPS
     * READ_PHONE_STATE - IMEI
     *
     * @param context context
     * @return permissions that app does not have, null if api is lower than 23 or all permission are acquired
     */
    fun checkTrackingPermissions(context: Context): Array<String>? {
        if (Build.VERSION.SDK_INT > 22) {
            val permissions = ArrayList<String>()
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)

            return if (permissions.size == 0) null else permissions.toTypedArray()

        }
        return null
    }

    /**
     * Checks whether user has agreed to privacy policy
     */
    fun hasAgreedToPrivacyPolicy(context: Context) = Preferences.getPref(context).getBoolean(context.getString(R.string.settings_privacy_policy_key), false)


    /**
     * Shows privacy policy agreement dialog if it wasn't agreed already
     *
     * @return True if user has agreed to privacy policy
     */
    suspend fun privacyPolicy(activity: FragmentActivity, init: (Bundle.() -> Unit)? = null): Boolean = suspendCoroutine {
        if (!hasAgreedToPrivacyPolicy(activity)) {
            val privacyFragment = FragmentPrivacyDialog.newInstance(init)
            privacyFragment.setContinuation(it)
            privacyFragment.show(activity.supportFragmentManager, "privacy_dialog")
        } else
            it.resume(true)
    }

    /**
     * Shows privacy policy agreement dialog with upload tailored text if it wasn't agreed already.
     *
     * @return True if user has agreed to privacy policy
     */
    suspend fun privacyPolicyEnableUpload(activity: FragmentActivity): Boolean = privacyPolicy(activity) {
        putInt(FragmentPrivacyDialog.BUNDLE_ADDITIONAL_TEXT, R.string.privacy_policy_agreement_autoup_description)
        putBoolean(FragmentPrivacyDialog.BUNDLE_SET_AUTOUP_IF_TRUE, true)
    }

    /**
     * Converts coordinate to string
     *
     * @param coordinate coordinate
     * @return stringified coordinate
     */
    fun coordinateToString(coordinate: Double): String {
        var coord = coordinate
        val degree = coord.toInt()
        coord = (coord - degree) * 60
        val minute = coord.toInt()
        coord = (coord - minute) * 60
        val second = coord.toInt()
        return String.format(Locale.ENGLISH, "%02d", degree) + "\u00B0 " + String.format(Locale.ENGLISH, "%02d", minute) + "' " + String.format(Locale.ENGLISH, "%02d", second) + "\""
    }

    /**
     * Checks if airplane mode is turned on
     *
     * @param context context
     * @return true if airplane mode is turned on
     */
    fun isAirplaneModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }

    /**
     * Checks if device is connecting or is connected to network
     *
     * @return true if connected or connecting
     */
    fun hasNetwork(context: Context): Boolean {
        val activeNetwork = context.connectivityManager.activeNetworkInfo
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting
    }

    /**
     * Returns whether satellite position is allowed
     * GNSS is universal term for global navigation satellite system
     *
     * @param context context
     * @return true if enabled
     */
    fun isGNSSEnabled(context: Context): Boolean =
            context.locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    /**
     * Checks if there is anything to track
     *
     * @param context context
     * @return true if at least one fo location, cell and wifi tracking is enabled
     */
    fun canTrack(context: Context): Boolean {
        val preferences = Preferences.getPref(context)
        return preferences.getBoolean(Preferences.PREF_TRACKING_LOCATION_ENABLED, Preferences.DEFAULT_TRACKING_LOCATION_ENABLED) ||
                preferences.getBoolean(Preferences.PREF_TRACKING_CELL_ENABLED, Preferences.DEFAULT_TRACKING_CELL_ENABLED) ||
                preferences.getBoolean(Preferences.PREF_TRACKING_WIFI_ENABLED, Preferences.DEFAULT_TRACKING_WIFI_ENABLED)
    }

    /**
     * Returns how old is supplied unix time in days
     *
     * @param time unix time in milliseconds
     * @return number of days as age (e.g. +50 = 50 days old)
     */
    fun getAgeInDays(time: Long): Int =
            ((System.currentTimeMillis() - time) / DAY_IN_MILLISECONDS).toInt()


    /**
     * Checks if play services are available
     *
     * @param context context
     * @return true if available
     */
    fun checkPlayServices(context: Context): Boolean {
        val playServicesResolutionRequest = 9000
        val api = GoogleApiAvailability.getInstance()
        val resultCode = api.isGooglePlayServicesAvailable(context)
        if (resultCode != ConnectionResult.SUCCESS) {
            if (api.isUserResolvableError(resultCode))
                api.getErrorDialog(context as Activity, resultCode, playServicesResolutionRequest).show()

            return false
        }
        return true
    }

    /**
     * Animate scroll to y coordinate
     *
     * @param viewGroup View group
     * @param y         target y coordinate
     * @param millis    duration of animation
     */
    fun verticalSmoothScrollTo(viewGroup: ViewGroup, y: Int, millis: Int) {
        ObjectAnimator.ofInt(viewGroup, "scrollY", viewGroup.scrollY, y).setDuration(millis.toLong()).start()
    }

    /**
     * Formats 1000 as 1 000
     *
     * @param number input number
     * @return formatted number
     */
    fun formatNumber(number: Int): String {
        val df = DecimalFormat("#,###,###")
        return df.format(number.toLong()).replace(",".toRegex(), " ")
    }

    /**
     * Formats 1000 as 1 000
     *
     * @param number input number
     * @return formatted number
     */
    fun formatNumber(number: Long): String {
        val df = DecimalFormat("#,###,###")
        return df.format(number).replace(",".toRegex(), " ")
    }

    /**
     * Hides software keyboard
     *
     * @param context Context
     * @param view     view that should have summoned the keyboard
     */
    fun hideSoftKeyboard(context: Context, view: View) {
        context.inputMethodManager.hideSoftInputFromWindow(view.applicationWindowToken, 0)
    }

    /**
     * Returns array of color state lists in this order: Default, Selected
     *
     * @param resources resources
     * @return array of color states
     */
    fun getSelectionStateLists(resources: Resources, theme: Resources.Theme): Array<ColorStateList> =
            arrayOf(ResourcesCompat.getColorStateList(resources, R.color.default_value, theme)!!.withAlpha(resources.getInteger(R.integer.inactive_alpha)), ResourcesCompat.getColorStateList(resources, R.color.selected_value, theme)!!)
}
