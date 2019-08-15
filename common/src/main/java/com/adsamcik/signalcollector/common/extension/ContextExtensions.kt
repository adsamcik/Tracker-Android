package com.adsamcik.signalcollector.common.extension

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment


/**
 * Starts new activity for result
 *
 * @param requestCode Request code
 * @param options Options bundle
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Activity.startActivity(
		requestCode: Int = -1,
		options: Bundle? = null,
		noinline init: Intent.() -> Unit = {}
) {
	val intent = newIntent<T>()
	intent.init()
	startActivityForResult(intent, requestCode, options)
}

/**
 * Starts new activity.
 * If [Fragment.getContext] is null, null reference exception will be thrown.
 *
 * @param options Options bundle
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Fragment.startActivity(
		options: Bundle? = null,
		noinline init: Intent.() -> Unit = {}
) {
	requireContext().startActivity<T>(options, init)
}

/**
 * Starts new activity
 *
 * @param options Options bundle
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Context.startActivity(
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
fun Context.startActivity(className: String) {
	val intent = Intent()
	intent.setClassName(this, className)
	startActivity(intent)
}

/**
 * Starts new activity
 *
 * @param className Path to the activity class
 */
fun Activity.startActivity(className: String) {
	val intent = Intent()
	intent.setClassName(this, className)
	startActivity(intent)
}


/**
 * Starts new service
 *
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Context.startService(
		noinline init: Intent.() -> Unit = {}
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
inline fun <reified T : Any> Context.startForegroundService(
		noinline init: Intent.() -> Unit = {}
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


fun Context.appVersion(): Long {
	val packageInfo = packageManager.getPackageInfo(packageName, 0)
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		packageInfo.longVersionCode
	} else {
		@Suppress("DEPRECATION")
		packageInfo.versionCode.toLong()
	}
}

fun Context.hasSelfPermission(permission: String): Boolean =
		ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasSelfPermissions(permissions: Collection<String>): BooleanArray =
		permissions.map { hasSelfPermission(it) }.toBooleanArray()

inline val Context.hasLocationPermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)

inline val Context.hasExternalStorageReadPermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)

inline val Context.hasExternalStorageWritePermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)

inline val Context.hasReadPhonePermission: Boolean
	get() =
		hasSelfPermission(Manifest.permission.READ_PHONE_STATE)


/**
 * Returns typed system service so no cast is necessary.
 * Uses getSystemService(name) system method.
 * Typed method in Android does name lookup (so it might be tiny bit slower) and is available from API 23.
 */
inline fun <reified T : Any> Context.getSystemServiceTyped(serviceName: String): T = getSystemService(serviceName) as T

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

val Context.applicationName: String
	get() {
		val applicationInfo = applicationInfo
		val stringId = applicationInfo.labelRes
		return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else getString(stringId)
	}

@Suppress("unused")
val Activity.tag: String
	get() = applicationName + this::class.java.simpleName

