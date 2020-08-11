package com.adsamcik.tracker.shared.base.assist

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.locationManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.*
import kotlin.math.ln
import kotlin.math.pow


/**
 * All purpose utility singleton containing various utility functions
 */
@Suppress("TooManyFunctions")
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
		val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
		val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
		return String.format(
				Locale.getDefault(),
				"%.1f %sB",
				bytes / unit.toDouble().pow(exp.toDouble()),
				pre
		)
	}

	/**
	 * Checks if required permission are available
	 * ACCESS_FINE_LOCATION - GPS
	 * READ_PHONE_STATE - IMEI
	 *
	 * @param context context
	 * @return permissions that app does not have, null if api is lower than 23 or all permission are acquired
	 */
	fun checkTrackingPermissions(context: Context): Array<String> {
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
			val permissions = ArrayList<String>()
			if (!context.hasLocationPermission) {
				permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
			}

			return permissions.toTypedArray()
		}
		return arrayOf()
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
		return String.format(Locale.ENGLISH, "%02d", degree) +
				"\u00B0 " +
				String.format(Locale.ENGLISH, "%02d", minute) +
				"' " +
				String.format(Locale.ENGLISH, "%02d", second) +
				"\""
	}

	/**
	 * Checks if airplane mode is turned on
	 *
	 * @param context context
	 * @return true if airplane mode is turned on
	 */
	fun isAirplaneModeEnabled(context: Context): Boolean {
		return Settings.Global.getInt(
				context.contentResolver,
				Settings.Global.AIRPLANE_MODE_ON, 0
		) != 0
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


	private const val PLAY_SERVICES_REQUEST_ID = 9000

	/**
	 * Checks if play services are available
	 *
	 * @param activity context
	 * @return true if available
	 */
	fun isPlayServicesAvailable(activity: Activity): Boolean {
		val playServicesResolutionRequest = PLAY_SERVICES_REQUEST_ID
		val api = GoogleApiAvailability.getInstance()
		val resultCode = api.isGooglePlayServicesAvailable(activity)
		if (resultCode != ConnectionResult.SUCCESS) {
			if (api.isUserResolvableError(resultCode)) {
				api.getErrorDialog(activity, resultCode, playServicesResolutionRequest).show()
			}

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
	fun isPlayServicesAvailable(context: Context): Boolean {
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
		ObjectAnimator.ofInt(viewGroup, "scrollY", viewGroup.scrollY, y)
				.setDuration(millis.toLong()).start()
	}

	fun ensureLooper() {
		if (Looper.myLooper() == null) Looper.prepare()
	}

	fun isMainThread(): Boolean {
		return Looper.myLooper() == Looper.getMainLooper()
	}

	fun getBackgroundDrawable(
			pressedColor: Int,
			backgroundDrawable: Drawable? = null,
			mask: Drawable? = null
	): RippleDrawable {
		return RippleDrawable(getPressedState(pressedColor), backgroundDrawable, mask)
	}

	fun getPressedState(pressedColor: Int): ColorStateList {
		return ColorStateList(arrayOf(intArrayOf()), intArrayOf(pressedColor))
	}
}
