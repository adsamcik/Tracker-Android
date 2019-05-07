package com.adsamcik.signalcollector.app

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.common.R
import com.adsamcik.signalcollector.common.misc.extension.locationManager
import com.adsamcik.signalcollector.common.misc.extension.windowManager
import com.adsamcik.signalcollector.common.misc.keyboard.NavBarPosition
import com.adsamcik.signalcollector.common.preference.Preferences
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.*


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
		if (bytes < unit) return "$bytes B"
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

		return preferences.getBooleanRes(R.string.settings_location_enabled_key, R.string.settings_location_enabled_default) ||
				preferences.getBooleanRes(R.string.settings_cell_enabled_key, R.string.settings_cell_enabled_default) ||
				preferences.getBooleanRes(R.string.settings_wifi_enabled_key, R.string.settings_wifi_enabled_default)
	}

	/**
	 * Checks if play services are available
	 *
	 * @param activity context
	 * @return true if available
	 */
	fun checkPlayServices(activity: Activity): Boolean {
		val playServicesResolutionRequest = 9000
		val api = GoogleApiAvailability.getInstance()
		val resultCode = api.isGooglePlayServicesAvailable(activity)
		if (resultCode != ConnectionResult.SUCCESS) {
			if (api.isUserResolvableError(resultCode))
				api.getErrorDialog(activity, resultCode, playServicesResolutionRequest).show()

			return false
		}
		return true
	}

	/**
	 * Checks if play services are available
	 *
	 * @param context context
	 * @return true if available
	 */
	fun checkPlayServices(context: Context): Boolean {
		val api = GoogleApiAvailability.getInstance()
		val resultCode = api.isGooglePlayServicesAvailable(context)
		return resultCode == ConnectionResult.SUCCESS
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
}
