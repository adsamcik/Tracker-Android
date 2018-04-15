package com.adsamcik.signalcollector.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.app.Fragment
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager

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
inline fun <reified T : Any> Fragment.startActivity(
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
 * Creates new transaction for a [android.support.v4.app.FragmentManager].
 * Transaction is committed using commit.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun android.support.v4.app.FragmentManager.transaction(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
    beginTransaction().func().commit()
}

/**
 * Creates new transaction for a [android.support.v4.app.FragmentManager].
 * Transaction is committed using commitAllowingStateLoss.
 *
 * @param func Specify all actions you want to do in this transaction (eg. replace(id, fragment))
 */
inline fun android.support.v4.app.FragmentManager.transactionStateLoss(func: android.support.v4.app.FragmentTransaction.() -> android.support.v4.app.FragmentTransaction) {
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
val Context.telephonyManager get() = getSystemServiceTyped<TelephonyManager>(Context.TELEPHONY_SERVICE)

/**
 * Shortcut to get [ConnectivityManager]. This property does not cache the service.
 */
val Context.connectivityManager get() = getSystemServiceTyped<ConnectivityManager>(Context.CONNECTIVITY_SERVICE)

/**
 * Shortcut to get [LocationManager]. This property does not cache the service.
 */
val Context.locationManager get() = getSystemServiceTyped<LocationManager>(Context.LOCATION_SERVICE)

/**
 * Shortcut to get [InputMethodManager]. This property does not cache the service.
 */
val Context.inputMethodManager get() = getSystemServiceTyped<InputMethodManager>(Context.INPUT_METHOD_SERVICE)

/**
 * Shortcut to get [WindowManager]. This property does not cache the service.
 */
val Context.windowManager get() = getSystemServiceTyped<WindowManager>(Context.WINDOW_SERVICE)

/**
 * Shortcut to get [PowerManager]. This property does not cache the service.
 */
val Context.powerManager get() = getSystemServiceTyped<PowerManager>(Context.POWER_SERVICE)