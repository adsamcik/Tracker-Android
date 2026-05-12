package com.adsamcik.tracker.shared.base.extension

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat




/**
 * Starts new activity
 *
 * @param options Options bundle
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : ComponentActivity> Context.startActivity(
	options: Bundle? = null,
	noinline init: Intent.() -> Unit = {}
) {
	val intent = newIntent<T>()
	intent.init()
	startActivity(intent, options)
}

/**
 * Starts new activity
 *
 * @param action Action
 * @param uri URI
 */
fun Context.startActivity(
	action: String,
	uri: Uri
) {
	val intent = Intent(action, uri)
	startActivity(intent)
}

/**
 * Starts new activity
 *
 * @param className Path to the activity class
 */
inline fun Context.startActivity(className: String, init: Intent.() -> Unit = {}) {
	val intent = Intent()
	intent.setClassName(this, className)
	intent.init()
	startActivity(intent)
}

/**
 * Starts new activity
 *
 * @param className Path to the activity class
 */
fun AppCompatActivity.startActivity(className: String, init: Intent.() -> Unit = {}) {
	val intent = Intent()
	intent.setClassName(this, className)
	intent.init()
	startActivity(intent)
}


/**
 * Starts new service
 *
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Service> Context.startService(
	init: Intent.() -> Unit = {}
) {
	val intent = newIntent<T>()
	intent.init()
	startService(intent)
}

/**
 * Starts new service in foreground
 *
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Service> Context.startForegroundService(
	init: Intent.() -> Unit = {}
) {
	val intent = newIntent<T>()
	intent.init()
	ContextCompat.startForegroundService(this, intent)
}

/**
 * Requests that a given service should be stopped.
 * Uses standard [Context.stopService] method.
 */
inline fun <reified T : Any> Context.stopService() {
	val intent = newIntent<T>()
	stopService(intent)
}


/**
 * Creates new intent for class of type [T]
 */
inline fun <reified T : Any> Context.newIntent(): Intent =
	Intent(this, T::class.java)


/**
 * Returns app version.
 */
fun Context.appVersion(): Long {
	val packageInfo = packageManager.getPackageInfo(packageName, 0)
	return PackageInfoCompat.getLongVersionCode(packageInfo)
}

/**
 * Checks if application has given permission.
 */
fun Context.hasSelfPermission(permission: String): Boolean =
	ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Checks if application has all permissions in a collection.
 */
fun Context.hasSelfPermissions(permissions: Collection<String>): BooleanArray =
	permissions.map { hasSelfPermission(it) }.toBooleanArray()

/**
 * Checks if application has any location permission (coarse or fine).
 * Accepts either ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION.
 */
inline val Context.hasLocationPermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
				hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * Checks if application has precise (fine) location permission.
 */
inline val Context.hasPreciseLocationPermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)

/**
 * Checks if application has coarse (approximate) location permission.
 */
inline val Context.hasCoarseLocationPermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

inline val Context.hasBackgroundLocationPermission: Boolean
	get() =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
				hasSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

inline val Context.hasActivityPermission: Boolean
	get() =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
				hasSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)

inline val Context.hasReadPhonePermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.READ_PHONE_STATE)

/**
 * Checks if application can collect Wi-Fi scan data.
 * On API 33+ this is gated by NEARBY_WIFI_DEVICES; older Android versions gate
 * Wi-Fi scan results behind location permission.
 */
inline val Context.hasWifiScanPermission: Boolean
	get() =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			hasSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
		} else {
			hasLocationPermission
		}

/**
 * Checks if application can collect cell/radio scan data.
 * Cell scan data can be joined with locations, so require both explicit phone
 * state access and precise location before collection.
 */
inline val Context.hasCellScanPermission: Boolean
	get() =
		hasReadPhonePermission && hasPreciseLocationPermission


/**
 * Returns typed system service so no cast is necessary.
 * Uses getSystemService(name) system method.
 * Typed method in Android does name lookup (so it might be tiny bit slower) and is available from API 23.
 */
inline fun <reified T : Any> Context.getSystemServiceTyped(serviceName: String): T =
	getSystemService(
		serviceName
	) as T

/**
 * Shortcut to get [TelephonyManager]. This property does not cache the service.
 */
inline val Context.telephonyManager: TelephonyManager get() = getSystemServiceTyped(Context.TELEPHONY_SERVICE)

/**
 * Shortcut to get [ConnectivityManager]. This property does not cache the service.
 */
inline val Context.connectivityManager: ConnectivityManager get() = getSystemServiceTyped(Context.CONNECTIVITY_SERVICE)

/**
 * Shortcut to get [LocationManager]. This property does not cache the service.
 */
inline val Context.locationManager: LocationManager get() = getSystemServiceTyped(Context.LOCATION_SERVICE)

/**
 * Shortcut to get [InputMethodManager]. This property does not cache the service.
 */
inline val Context.inputMethodManager: InputMethodManager get() = getSystemServiceTyped(Context.INPUT_METHOD_SERVICE)

/**
 * Shortcut to get [WindowManager]. This property does not cache the service.
 */
inline val Context.windowManager: WindowManager get() = getSystemServiceTyped(Context.WINDOW_SERVICE)

/**
 * Shortcut to get [PowerManager]. This property does not cache the service.
 */
inline val Context.powerManager: PowerManager get() = getSystemServiceTyped(Context.POWER_SERVICE)

/**
 * Shortcut to get [JobScheduler]. This property does not cache the service.
 */
inline val Context.alarmManager: AlarmManager get() = getSystemServiceTyped(Context.ALARM_SERVICE)

/**
 * Shortcut to get [WifiManager]. This property does not cache the service.
 */
inline val Context.wifiManager: WifiManager get() = getSystemServiceTyped(Context.WIFI_SERVICE)

/**
 * Shortcut to get [ShortcutManager]. This property does not cache the service.
 */
inline val Context.shortcutManager: ShortcutManager
	@RequiresApi(25) get() = getSystemServiceTyped(Context.SHORTCUT_SERVICE)

/**
 * Shortcut to get [ShortcutManager]. This property does not cache the service.
 */
inline val Context.sensorManager: SensorManager get() = getSystemServiceTyped(Context.SENSOR_SERVICE)

/**
 * Shortcut to get [NotificationManager]. This property does not cache the service.
 */
inline val Context.notificationManager: NotificationManager get() = getSystemServiceTyped(Context.NOTIFICATION_SERVICE)

/**
 * Shortcut to get [LayoutInflater]. This property does not cache the service.
 */
inline val Context.layoutInflater: LayoutInflater get() = getSystemServiceTyped(Context.LAYOUT_INFLATER_SERVICE)

/**
 * Name of the application
 */
val Context.applicationName: String
	get() {
		val applicationInfo = applicationInfo
		val stringId = applicationInfo.labelRes
		return if (stringId == 0) {
			applicationInfo.nonLocalizedLabel.toString()
		} else {
			getString(stringId)
		}
	}


