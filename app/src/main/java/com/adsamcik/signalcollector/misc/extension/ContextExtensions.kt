package com.adsamcik.signalcollector.misc.extension

import android.app.Activity
import android.app.AlarmManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction

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
        noinline init: Intent.() -> Unit = {}) {
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
inline fun <reified T : Any> androidx.fragment.app.Fragment.startActivity(
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {
    context!!.startActivity<T>(options, init)
}

/**
 * Starts new activity
 *
 * @param options Options bundle
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Context.startActivity(
        options: Bundle? = null,
        noinline init: Intent.() -> Unit = {}) {

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
        uri: Uri) {
    val intent = Intent(action, uri)
    startActivity(intent)
}

/**
 * Starts new service
 *
 * @param init Initialization function to setup the intent if needed
 */
inline fun <reified T : Any> Context.startService(
        noinline init: Intent.() -> Unit = {}) {
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
        noinline init: Intent.() -> Unit = {}) {
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
    return if (Build.VERSION.SDK_INT >= 28)
        packageInfo.longVersionCode
    else
        packageInfo.versionCode.toLong()
}

/**
 * Creates new transaction for a [FragmentManager].
 * Transaction is committed using commit.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun FragmentManager.transaction(func: FragmentTransaction.() -> FragmentTransaction) {
    beginTransaction().func().commit()
}

/**
 * Creates new transaction for a [FragmentManager].
 * Transaction is committed using commitAllowingStateLoss.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun FragmentManager.transactionStateLoss(func: FragmentTransaction.() -> FragmentTransaction) {
    beginTransaction().func().commitAllowingStateLoss()
}

/**
 * Returns typed system service so no cast is necessary.
 * Uses getSystemService(name) system method. Typed method in Android does name lookup (so it might be tiny bit slower) and is available from API 23.
 */
inline fun <reified T : Any> Context.getSystemServiceTyped(serviceName: String): T = getSystemService(serviceName) as T

/**
 * Shortcut to get [TelephonyManager]. This property does not cache the service.
 */
inline val Context.telephonyManager get() = getSystemServiceTyped<TelephonyManager>(Context.TELEPHONY_SERVICE)

/**
 * Shortcut to get [ConnectivityManager]. This property does not cache the service.
 */
inline val Context.connectivityManager get() = getSystemServiceTyped<ConnectivityManager>(Context.CONNECTIVITY_SERVICE)

/**
 * Shortcut to get [LocationManager]. This property does not cache the service.
 */
inline val Context.locationManager get() = getSystemServiceTyped<LocationManager>(Context.LOCATION_SERVICE)

/**
 * Shortcut to get [InputMethodManager]. This property does not cache the service.
 */
inline val Context.inputMethodManager get() = getSystemServiceTyped<InputMethodManager>(Context.INPUT_METHOD_SERVICE)

/**
 * Shortcut to get [WindowManager]. This property does not cache the service.
 */
inline val Context.windowManager get() = getSystemServiceTyped<WindowManager>(Context.WINDOW_SERVICE)

/**
 * Shortcut to get [PowerManager]. This property does not cache the service.
 */
inline val Context.powerManager get() = getSystemServiceTyped<PowerManager>(Context.POWER_SERVICE)

/**
 * Shortcut to get [JobScheduler]. This property does not cache the service.
 */
inline val Context.alarmManager get() = getSystemServiceTyped<AlarmManager>(Context.ALARM_SERVICE)

/**
 * Shortcut to get [ShortcutManager]. This property does not cache the service.
 */
inline val Context.shortcutManager @RequiresApi(25) get() = getSystemServiceTyped<ShortcutManager>(Context.SHORTCUT_SERVICE)