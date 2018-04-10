package com.adsamcik.signalcollector.utility

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.support.annotation.ColorInt
import android.support.v4.content.ContextCompat
import android.support.v4.content.res.ResourcesCompat
import android.telephony.TelephonyManager
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.utility.Constants.DAY_IN_MILLISECONDS
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.text.DecimalFormat
import java.util.*

object Assist {
    private var telephonyManager: TelephonyManager? = null
    private var connectivityManager: ConnectivityManager? = null

    val isInitialized: Boolean
        get() = telephonyManager != null && connectivityManager != null

    /**
     * Initializes TelephonyManager and ConnectivityManager in Assist class
     *
     * @param c context
     */
    fun initialize(c: Context) {
        telephonyManager = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

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

    fun getAppUsableScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        return size
    }

    fun getRealScreenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    enum class NavBarPosition {
        BOTTOM,
        LEFT,
        RIGHT,
        UNKNOWN
    }

    fun orientation(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }

    fun navbarSize(context: Context): Pair<NavBarPosition, Point> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = windowManager.defaultDisplay

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

            //if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            //permissions.add(android.Manifest.permission.READ_PHONE_STATE);

            if (Preferences.getPref(context).getBoolean(Preferences.PREF_TRACKING_NOISE_ENABLED, false) && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(android.Manifest.permission.RECORD_AUDIO)

            return if (permissions.size == 0) null else permissions.toTypedArray()

        }
        return null
    }

    /**
     * Converts amplitude to dbm
     *
     * @param amplitude amplitude
     * @return dbm
     */
    fun amplitudeToDbm(amplitude: Short): Double =
            20 * Math.log10(Math.abs(amplitude.toInt()).toDouble())

    /**
     * Converts coordinate to string
     *
     * @param coordinate coordinate
     * @return stringified coordinate
     */
    fun coordsToString(coordinate: Double): String {
        var coord = coordinate
        val degree = coord.toInt()
        coord = (coord - degree) * 60
        val minute = coord.toInt()
        coord = (coord - minute) * 60
        val second = coord.toInt()
        return String.format(Locale.ENGLISH, "%02d", degree) + "\u00B0 " + String.format(Locale.ENGLISH, "%02d", minute) + "' " + String.format(Locale.ENGLISH, "%02d", second) + "\""
    }

    fun startServiceForeground(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26)
            context.startForegroundService(intent)
        else
            context.startService(intent)
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
        if (connectivityManager == null)
            initialize(context)
        val activeNetwork = connectivityManager!!.activeNetworkInfo
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
            (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)

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


    fun invertColor(@ColorInt color: Int): Int =
            Color.argb(Color.alpha(color), 255 - Color.red(color), 255 - Color.green(color), 255 - Color.blue(color))

    /**
     * Animate smooth scroll to y coordinate
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
     * @param activity activity
     * @param view     view that should have summoned the keyboard
     */
    fun hideSoftKeyboard(activity: Activity, view: View) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.applicationWindowToken, 0)
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
